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

package com.lin.agent.core.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One photo on the phone. */
data class Photo(val uri: String, val takenAt: Long)

/**
 * The phone's photos, and the bytes to embed them from.
 *
 * Reading stops at the pixels: nothing here leaves the device, and the index this feeds holds
 * vectors and content URIs rather than copies of anybody's pictures. Worth stating plainly,
 * because "let an assistant look through my photo library" is a sentence that deserves to be
 * false in every way except the literal one.
 */
class PhotoLibrary(private val context: Context) {

  /**
   * Photos newest first.
   *
   * Newest first because that is the order somebody notices: a half-finished index that covers
   * this year is useful, and one that covers 2009 is not.
   */
  suspend fun photos(limit: Int = Int.MAX_VALUE): List<Photo> =
    withContext(Dispatchers.IO) {
      val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
      runCatching {
          context.contentResolver
            .query(
              MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              columns,
              null,
              null,
              "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )
            ?.use { cursor ->
              buildList {
                while (cursor.moveToNext() && size < limit) {
                  val id = cursor.getLong(0)
                  add(
                    Photo(
                      uri =
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id,
                          )
                          .toString(),
                      takenAt = cursor.getLong(1),
                    )
                  )
                }
              }
            }
        }
        .onFailure { Log.w(TAG, "Could not read the photo library", it) }
        .getOrNull()
        .orEmpty()
    }

  /**
   * A photo as bytes small enough to embed, or null if it cannot be read.
   *
   * Downscaled first, and that is not an optimisation. A twelve megapixel JPEG is tens of
   * megabytes decoded, the encoder sees a few hundred pixels square whatever it is given, and
   * indexing thousands of them at full size is the difference between a background job and a
   * phone that gets hot and gives up.
   */
  suspend fun bytesFor(uri: String): ByteArray? =
    withContext(Dispatchers.IO) {
      runCatching {
          val parsed = Uri.parse(uri)
          val bitmap: Bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              context.contentResolver.loadThumbnail(parsed, Size(SIDE, SIDE), null)
            } else {
              @Suppress("DEPRECATION")
              MediaStore.Images.Media.getBitmap(context.contentResolver, parsed)
            }
          ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
          }
        }
        .onFailure { Log.w(TAG, "Could not read $uri", it) }
        .getOrNull()
    }

  private companion object {
    const val TAG = "LinPhotoLibrary"

    /**
     * The square the thumbnail is fitted into.
     *
     * Larger than the encoder's own input on purpose: the system thumbnail is cheap and
     * cropping to the model's exact size is the engine's business, while a thumbnail smaller
     * than it wants cannot be recovered.
     */
    const val SIDE = 512

    const val QUALITY = 85
  }
}
