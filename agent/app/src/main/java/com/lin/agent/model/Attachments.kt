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
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.lin.agent.core.documents.DocRead
import com.lin.agent.core.documents.Documents
import com.lin.agent.core.attachments.sampleSizeFor
import com.lin.agent.engine.Attachment
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Attaching something failed, with a reason for the person rather than a stack trace. */
data class AttachFailed(val why: String)

/**
 * Turns something the user picked into something a model can be given.
 *
 * A document becomes text here, which is why it then works against every backend without any
 * of them knowing about documents. A picture stays a picture and gets smaller.
 */
@Singleton
class Attachments @Inject constructor(private val documents: Documents) {

  /** Reads a document, or says why not. */
  suspend fun document(uri: Uri, context: Context): Result<Attachment.Document> =
    when (val read = documents.read(uri)) {
      is DocRead.Ok -> Result.success(Attachment.Document(read.name, read.text))
      is DocRead.Failed -> Result.failure(IllegalArgumentException(read.why))
    }

  /**
   * Reads a picture and shrinks it.
   *
   * Shrinking is not an optimisation. A phone camera produces twelve megapixels and four
   * megabytes; a vision encoder sees a few hundred pixels a side whatever it is given, so the
   * rest is a slow base64 string that makes the request enormous and, on-device, a bitmap big
   * enough to matter on a phone already holding two and a half gigabytes of weights.
   *
   * Sampled down while decoding rather than afterwards, because decoding it at full size first
   * is the part that would run the app out of memory.
   */
  suspend fun picture(uri: Uri, context: Context): Result<Attachment.Picture> =
    withContext(Dispatchers.IO) {
      runCatching {
          val name = documents.nameOf(uri)

          val bounds =
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
              context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
              }
            }
          val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, LONGEST_SIDE)

          val bitmap =
            context.contentResolver
              .openInputStream(uri)
              ?.use {
                BitmapFactory.decodeStream(
                  it,
                  null,
                  BitmapFactory.Options().apply { inSampleSize = sample },
                )
              }
              ?: throw IllegalStateException("The picture could not be opened.")

          val encoded =
            ByteArrayOutputStream().also {
              bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
            }
          bitmap.recycle()

          Attachment.Picture(name, encoded.toByteArray())
        }
        .onFailure { Log.w(TAG, "Could not read a picture", it) }
        .recoverCatching {
          throw IllegalArgumentException("That picture could not be read.")
        }
    }

  private companion object {
    const val TAG = "LinAttachments"

    /** Comfortably more than any vision encoder uses, and small enough to send. */
    const val LONGEST_SIDE = 1024

    const val QUALITY = 85
  }
}
