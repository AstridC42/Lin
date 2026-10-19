/*
 * Copyright 2026 The Lin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lin.agent.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.R
import com.lin.agent.core.media.CutOut
import com.lin.agent.core.models.DownloadState
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelDownloads
import com.lin.agent.engine.Attachment
import com.lin.agent.model.Segmenter
import com.lin.agent.model.Tap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the cut-out screen shows. */
data class CutOutState(
  val photo: Bitmap? = null,
  val taps: List<Tap> = emptyList(),
  /** The photo darkened outside the selection, for showing what is selected. */
  val overlay: Bitmap? = null,
  val selected: Boolean = false,
  val busy: Boolean = false,
  /** True while taps take away from the selection rather than add to it. */
  val removing: Boolean = false,
  val modelReady: Boolean = false,
  val download: DownloadState = DownloadState.Idle,
  /** A sentence to show once: saved, or what went wrong. */
  val message: String = "",
)

/**
 * Cutting an object out of a photo: tap it, and Lin outlines it on the phone with the Magic
 * Touch model. Then save it as a transparent PNG, blur everything around it, share it, or hand
 * it to Lin as a picture to ask about.
 */
@HiltViewModel
class CutOutViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val segmenter: Segmenter,
  private val downloads: ModelDownloads,
) : ViewModel() {

  private val _state = MutableStateFlow(CutOutState(modelReady = segmenter.ready()))
  val state: StateFlow<CutOutState> = _state.asStateFlow()

  /** The current selection's opacity per pixel, at the photo's size. */
  private var alpha: IntArray? = null
  private var pixels: IntArray? = null

  init {
    viewModelScope.launch {
      downloads.watch(ModelCatalog.MAGIC_TOUCH).collect { download ->
        _state.update { it.copy(download = download, modelReady = segmenter.ready()) }
        if (download is DownloadState.Done) reselect()
      }
    }
  }

  fun open(uri: Uri) {
    viewModelScope.launch {
      val photo =
        withContext(Dispatchers.IO) {
          runCatching {
              ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // Software, so the pixels can be read; capped, so a 50 MP photo stays quick.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > LONGEST_SIDE) {
                  val scale = LONGEST_SIDE.toFloat() / longest
                  decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
              }.copy(Bitmap.Config.ARGB_8888, false)
            }
            .onFailure { Log.w(TAG, "Could not open the photo", it) }
            .getOrNull()
        }
      if (photo == null) {
        _state.update { it.copy(message = context.getString(R.string.cutout_open_failed)) }
        return@launch
      }
      pixels = IntArray(photo.width * photo.height).also { photo.getPixels(it, 0, photo.width, 0, 0, photo.width, photo.height) }
      alpha = null
      _state.update { CutOutState(photo = photo, modelReady = segmenter.ready(), download = it.download) }
    }
  }

  fun getModel() = downloads.start(ModelCatalog.MAGIC_TOUCH)

  fun setRemoving(on: Boolean) = _state.update { it.copy(removing = on) }

  /** A tap at [x], [y] in 0..1 of the photo. */
  fun tap(x: Float, y: Float) {
    if (!_state.value.modelReady || _state.value.photo == null) return
    _state.update { it.copy(taps = it.taps + Tap(x, y, keep = !it.removing)) }
    reselect()
  }

  fun undo() {
    _state.update { it.copy(taps = it.taps.dropLast(1)) }
    reselect()
  }

  fun clear() {
    alpha = null
    _state.update { it.copy(taps = emptyList(), overlay = null, selected = false) }
  }

  fun shown() = _state.update { it.copy(message = "") }

  private fun reselect() {
    val photo = _state.value.photo ?: return
    val taps = _state.value.taps
    if (taps.none { it.keep }) {
      clear()
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(busy = true) }
      val confidence = runCatching { segmenter.select(photo, taps) }.onFailure { Log.w(TAG, "Segmenting failed", it) }.getOrNull()
      if (confidence == null) {
        _state.update { it.copy(busy = false, message = context.getString(R.string.cutout_failed)) }
        return@launch
      }
      val selection = withContext(Dispatchers.Default) { CutOut.alphaFrom(confidence) }
      alpha = selection
      val overlay =
        withContext(Dispatchers.Default) {
          // Outside the selection is dimmed; the selection itself shows as it is.
          val shade = IntArray(selection.size) { i -> ((0xB0 * (255 - selection[i]) / 255) shl 24) }
          Bitmap.createBitmap(shade, photo.width, photo.height, Bitmap.Config.ARGB_8888)
        }
      _state.update {
        it.copy(busy = false, overlay = overlay, selected = CutOut.anythingSelected(selection))
      }
    }
  }

  /** The object alone on transparency, cropped to it. */
  private fun cutOutBitmap(): Bitmap? {
    val photo = _state.value.photo ?: return null
    val a = alpha ?: return null
    val px = pixels ?: return null
    val box = CutOut.bounds(a, photo.width, photo.height) ?: return null
    val cut = CutOut.crop(CutOut.cutOut(px, a), photo.width, box)
    return Bitmap.createBitmap(cut, box.width, box.height, Bitmap.Config.ARGB_8888)
  }

  /** The whole photo with everything but the object blurred. */
  private fun blurredBitmap(): Bitmap? {
    val photo = _state.value.photo ?: return null
    val a = alpha ?: return null
    val px = pixels ?: return null
    // Blurred small and scaled back up: the same look at a fraction of the work.
    val small = Bitmap.createScaledBitmap(photo, (photo.width / 4).coerceAtLeast(1), (photo.height / 4).coerceAtLeast(1), true)
    val sp = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    val blurredSmall = Bitmap.createBitmap(CutOut.blur(sp, small.width, small.height, 4), small.width, small.height, Bitmap.Config.ARGB_8888)
    val back = Bitmap.createScaledBitmap(blurredSmall, photo.width, photo.height, true)
    val bp = IntArray(px.size).also { back.getPixels(it, 0, photo.width, 0, 0, photo.width, photo.height) }
    return Bitmap.createBitmap(CutOut.blend(px, bp, a), photo.width, photo.height, Bitmap.Config.ARGB_8888)
  }

  fun saveCutOut() = save(R.string.cutout_saved) { cutOutBitmap()?.let { it to Bitmap.CompressFormat.PNG } }

  fun saveBlurred() = save(R.string.cutout_blur_saved) { blurredBitmap()?.let { it to Bitmap.CompressFormat.JPEG } }

  private fun save(done: Int, make: () -> Pair<Bitmap, Bitmap.CompressFormat>?) {
    viewModelScope.launch {
      _state.update { it.copy(busy = true) }
      val saved =
        withContext(Dispatchers.Default) {
          runCatching {
              val (bitmap, format) = make() ?: return@runCatching false
              val png = format == Bitmap.CompressFormat.PNG
              val values =
                ContentValues().apply {
                  put(MediaStore.Images.Media.DISPLAY_NAME, "lin-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
                  put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                  put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lin")
                }
              val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
              context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(format, if (png) 100 else 92, it) } ?: return@runCatching false
              true
            }
            .onFailure { Log.w(TAG, "Saving failed", it) }
            .getOrDefault(false)
        }
      _state.update {
        it.copy(busy = false, message = context.getString(if (saved) done else R.string.cutout_save_failed))
      }
    }
  }

  /** A share sheet for the cut-out, as a PNG. */
  fun share(launch: (Intent) -> Unit) {
    viewModelScope.launch {
      val uri =
        withContext(Dispatchers.Default) {
          runCatching {
              val bitmap = cutOutBitmap() ?: return@runCatching null
              val folder = File(context.filesDir, "documents/cutouts").apply { mkdirs() }
              folder.listFiles()?.forEach { it.delete() }
              val file = File(folder, "cut-out.png")
              file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
              FileProvider.getUriForFile(context, "${context.packageName}.documents", file)
            }
            .getOrNull()
        }
      if (uri == null) {
        _state.update { it.copy(message = context.getString(R.string.cutout_save_failed)) }
        return@launch
      }
      launch(
        Intent.createChooser(
          Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
          null,
        )
      )
    }
  }

  /** The cut-out as a picture for the conversation, on white, since models do not read alpha. */
  suspend fun asPicture(): Attachment.Picture? =
    withContext(Dispatchers.Default) {
      val cut = cutOutBitmap() ?: return@withContext null
      val px = IntArray(cut.width * cut.height).also { cut.getPixels(it, 0, cut.width, 0, 0, cut.width, cut.height) }
      val flat = Bitmap.createBitmap(CutOut.onPlain(px), cut.width, cut.height, Bitmap.Config.ARGB_8888)
      val bytes = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
      Attachment.Picture("cut-out.jpg", bytes)
    }

  override fun onCleared() {
    segmenter.release()
    super.onCleared()
  }

  private companion object {
    const val TAG = "LinCutOut"
    const val LONGEST_SIDE = 1280
  }
}
