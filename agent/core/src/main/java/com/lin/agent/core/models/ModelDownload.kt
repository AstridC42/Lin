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

package com.lin.agent.core.models

import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How far a download has got. */
data class DownloadProgress(val receivedBytes: Long, val totalBytes: Long) {
  val percent: Int
    get() = if (totalBytes <= 0) 0 else ((receivedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** How a download ended. */
sealed interface DownloadOutcome {
  data object Done : DownloadOutcome

  /** [retryable] distinguishes a flaky connection from a wrong URL or a full disk. */
  data class Failed(val message: String, val retryable: Boolean) : DownloadOutcome
}

/**
 * Fetches a model file, resumably.
 *
 * These are two to three gigabyte files over a phone connection, so resuming is not a nicety: a
 * download that restarts from zero every time the signal drops will never finish, and a user who
 * has spent two gigabytes of their allowance on a failed attempt will not try again.
 *
 * So bytes land in a `.part` file beside the destination and the next attempt asks for a byte
 * range starting where that file ends. The real name only appears once the whole thing is there
 * and the length matches - which means a half-finished file can never be handed to the engine,
 * where it would be a native crash rather than an error.
 *
 * No authentication. The Gemma and EmbeddingGemma builds used here are Apache-2.0 and ungated,
 * which was worth checking: it removes an OAuth flow, a token store and a licence-acceptance
 * screen from this app entirely.
 *
 * [openConnection] is injectable so the whole thing is testable without a network.
 */
class ModelDownload(
  private val openConnection: (url: String, rangeFrom: Long) -> Connection = ::httpConnection
) {

  /** The small surface of an HTTP response this needs, so a test can supply one. */
  interface Connection : AutoCloseable {
    /** HTTP status. 200 for a whole file, 206 when a range was honoured. */
    val status: Int

    /** Bytes this response carries, or -1 when the server did not say. */
    val contentLength: Long

    val body: InputStream
  }

  /**
   * Downloads [url] to [destination], reporting progress.
   *
   * [expectedBytes] is checked at the end. A file that arrives complete but the wrong length is
   * not the model it claims to be - a changed upload, or a captive portal's login page served with
   * a 200 - and either would crash the engine rather than fail politely.
   */
  suspend fun fetch(
    url: String,
    destination: File,
    expectedBytes: Long,
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadOutcome =
    withContext(Dispatchers.IO) {
      val part = File(destination.parentFile, destination.name + PART_SUFFIX)
      destination.parentFile?.mkdirs()

      var received = part.takeIf { it.exists() }?.length() ?: 0L
      if (received > expectedBytes && expectedBytes > 0) {
        // Longer than the whole file: a stale part from a different version of this model. Start
        // again rather than resume into nonsense.
        Log.w(TAG, "Discarding an over-long part file for ${destination.name}")
        part.delete()
        received = 0L
      }

      try {
        openConnection(url, received).use { connection ->
          // 206 means the range was honoured. 200 means it was not, and the body is the whole
          // file - so anything already on disk has to be thrown away rather than appended to.
          val resuming = connection.status == HTTP_PARTIAL
          if (!resuming && received > 0) {
            Log.i(TAG, "Server ignored the range request; starting ${destination.name} again")
            part.delete()
            received = 0L
          }
          if (connection.status != HTTP_OK && connection.status != HTTP_PARTIAL) {
            return@withContext DownloadOutcome.Failed(
              message = describeStatus(connection.status),
              // 4xx is a wrong request and will stay wrong; 5xx and anything else may not.
              retryable = connection.status !in 400..499,
            )
          }

          val total =
            if (expectedBytes > 0) expectedBytes
            else connection.contentLength.takeIf { it > 0 }?.plus(received) ?: -1L

          onProgress(DownloadProgress(received, total))

          connection.body.use { input ->
            java.io.FileOutputStream(part, received > 0).use { output ->
              val buffer = ByteArray(BUFFER_BYTES)
              var sinceReport = 0L
              while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                received += read
                sinceReport += read
                // Reported in chunks rather than per read: this runs tens of thousands of times
                // for a file this size, and a UI update per buffer is its own performance problem.
                if (sinceReport >= REPORT_EVERY_BYTES) {
                  onProgress(DownloadProgress(received, total))
                  sinceReport = 0
                }
              }
              output.flush()
            }
          }
          onProgress(DownloadProgress(received, total))
        }
      } catch (e: CancellationException) {
        // Paused or the worker was stopped. The part file stays, which is the whole point.
        throw e
      } catch (e: Throwable) {
        Log.w(TAG, "Download of ${destination.name} interrupted", e)
        return@withContext DownloadOutcome.Failed(
          message = e.message ?: e::class.java.simpleName,
          retryable = true,
        )
      }

      if (expectedBytes > 0 && part.length() != expectedBytes) {
        return@withContext DownloadOutcome.Failed(
          message =
            "The file arrived the wrong size: ${part.length()} bytes instead of $expectedBytes.",
          // Retryable, because the usual cause is a truncated body rather than a wrong URL.
          retryable = true,
        )
      }

      // Only now does it get the real name. Renaming is atomic on the same filesystem, so there
      // is no moment where a partial file exists under the name the engine will load.
      if (destination.exists()) destination.delete()
      if (!part.renameTo(destination)) {
        return@withContext DownloadOutcome.Failed(
          message = "The download finished but could not be saved.",
          retryable = false,
        )
      }
      DownloadOutcome.Done
    }

  private fun describeStatus(status: Int): String =
    when (status) {
      401, 403 -> "The server refused the download ($status)."
      404 -> "That model file is not where the app expected it ($status)."
      in 500..599 -> "The server had a problem ($status). Worth trying again."
      else -> "The download failed with status $status."
    }

  companion object {
    private const val TAG = "LinModelDownload"
    private const val PART_SUFFIX = ".part"
    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL = 206
    private const val BUFFER_BYTES = 1 shl 16
    private const val REPORT_EVERY_BYTES = 2L * 1024 * 1024

    /** Where a catalogue entry's file lives on Hugging Face. */
    fun urlFor(model: CatalogModel, socModel: String = currentSocModel()): String =
      "https://huggingface.co/${model.repoId}/resolve/main/${model.fileFor(socModel)}"

    private fun httpConnection(url: String, rangeFrom: Long): Connection {
      val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 30_000
        readTimeout = 30_000
        if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
      }
      return object : Connection {
        override val status: Int = connection.responseCode
        override val contentLength: Long = connection.contentLengthLong
        override val body: InputStream = connection.inputStream
        override fun close() = connection.disconnect()
      }
    }
  }
}
