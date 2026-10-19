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

package com.lin.agent.model

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A tap on the photo, in 0..1 of its width and height, and whether it adds or takes away. */
data class Tap(val x: Float, val y: Float, val keep: Boolean = true)

/**
 * Finds the object at the places tapped in a photo: Google's Magic Touch model through
 * MediaPipe's interactive segmenter, entirely on the phone.
 *
 * The photo is handed over once and then any number of tap sets are asked about it, which is
 * how the segmenter is built to be used - the expensive encoding of the photo happens once.
 */
@Singleton
class Segmenter
@Inject
constructor(@ApplicationContext private val context: Context, private val store: ModelStore) {

  private val lock = Mutex()
  private var segmenter: InteractiveSegmenter? = null
  private var current: Bitmap? = null

  fun ready(): Boolean = store.isPresent(ModelCatalog.MAGIC_TOUCH)

  /**
   * How sure the model is, pixel by pixel, that each belongs to the object, at the photo's size.
   * Null when nothing was tapped.
   */
  suspend fun select(photo: Bitmap, taps: List<Tap>): FloatArray? {
    if (taps.isEmpty()) return null
    return withContext(Dispatchers.Default) {
      lock.withLock {
        val engine = segmenter ?: load().also { segmenter = it }
        if (current !== photo) {
          engine.setImage(BitmapImageBuilder(photo).build())
          current = photo
        }
        // One stroke per tap: a stroke is a gesture, and each tap is its own.
        val strokes =
          taps.map { tap ->
            Stroke.builder()
              .setBrushMode(if (tap.keep) Stroke.BrushMode.POSITIVE else Stroke.BrushMode.NEGATIVE)
              .setPoints(listOf(NormalizedKeypoint.create(tap.x.coerceIn(0f, 1f), tap.y.coerceIn(0f, 1f))))
              .setCompleted(true)
              .build()
          }
        val mask = engine.segment(strokes)
        val buffer = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
        FloatArray(photo.width * photo.height).also { buffer.get(it, 0, minOf(it.size, buffer.remaining())) }
      }
    }
  }

  /**
   * Lets the model go, for when the screen that uses it closes. On its own scope, because the
   * screen's is already cancelled by the time it asks.
   */
  fun release() {
    releaser.launch {
      lock.withLock {
        runCatching { segmenter?.close() }
        segmenter = null
        current = null
      }
    }
  }

  private val releaser = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun load(): InteractiveSegmenter =
    InteractiveSegmenter.createFromOptions(
      context,
      InteractiveSegmenterOptions.builder()
        .setBaseOptions(
          BaseOptions.builder().setModelAssetPath(store.fileFor(ModelCatalog.MAGIC_TOUCH).absolutePath).build()
        )
        .build(),
    )
}
