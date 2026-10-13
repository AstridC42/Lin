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

package com.lin.agent.core.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What came of trying to read a document. */
sealed interface DocRead {
  data class Ok(val name: String, val text: String) : DocRead

  /** [why] is for the user and for the model, so it says what to do rather than what failed. */
  data class Failed(val why: String) : DocRead
}

/**
 * Reads documents the user has pointed at.
 *
 * Nothing is copied. A document stays where its owner put it and is read through its URI each
 * time, which keeps one copy of somebody's contract on their phone rather than two - and means
 * deleting it in their file manager actually deletes it.
 *
 * All of it off the main thread. A fifty-page PDF takes seconds to extract, and a document is
 * always opened in response to a tap.
 */
class Documents(private val context: Context) {

  /**
   * The text of a document, or why not.
   *
   * [forReading] is applied here rather than left to the caller, because every caller is about
   * to put this in front of a model with a few thousand tokens of context, and one that forgot
   * would fail as "the model is broken" rather than "your file is long".
   */
  suspend fun read(uri: Uri): DocRead =
    withContext(Dispatchers.IO) {
      val name = nameOf(uri)
      try {
        when (val kind = kindOf(name, context.contentResolver.getType(uri))) {
          DocKind.TEXT -> DocRead.Ok(name, forReading(tidy(readBytes(uri).decodeToString())))
          DocKind.DOCX -> DocRead.Ok(name, forReading(textFromDocx(readBytes(uri))))
          DocKind.PDF -> DocRead.Ok(name, forReading(textFromPdf(uri)))
          DocKind.UNKNOWN ->
            DocRead.Failed(
              "\"$name\" is not a kind of document this can read. It reads PDF, Word (.docx) " +
                "and plain text. An older .doc has to be saved as .docx first."
            ).also { Log.i(TAG, "Not readable: $name ($kind)") }
        }
      } catch (e: SecurityException) {
        // The persisted permission went away: the file was moved, or the app that shared it was
        // uninstalled. Worth distinguishing, because the fix is to add it again.
        Log.w(TAG, "No longer permitted to read $name", e)
        DocRead.Failed("Lin can no longer open \"$name\". Add it again to read it.")
      } catch (e: Throwable) {
        Log.w(TAG, "Could not read $name", e)
        DocRead.Failed("\"$name\" could not be read. It may be damaged or password-protected.")
      }
    }

  /** The display name a provider gives, falling back to the last path segment. */
  fun nameOf(uri: Uri): String {
    val queried =
      runCatching {
          context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
          }
        }
        .getOrNull()
    return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
      ?: "document"
  }

  private fun readBytes(uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      ?: throw IllegalStateException("No stream for $uri")

  /**
   * The text of a .docx, which is a zip with the prose in one entry.
   *
   * Streamed rather than unpacked to disk. The entry is found by name while scanning, so a
   * document with a hundred embedded images never has them read.
   */
  private fun textFromDocx(bytes: ByteArray): String {
    ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == DOCX_BODY) return textFromDocxXml(zip.readBytes().decodeToString())
      }
    }
    // A zip with no document.xml is not a Word file, whatever it is called.
    throw IllegalStateException("No $DOCX_BODY in the archive")
  }

  private fun textFromPdf(uri: Uri): String {
    // Needed once per process before PdfBox will touch a font. Idempotent, and cheap after the
    // first call, so it is done here rather than in an initialiser that a worker might skip.
    PDFBoxResourceLoader.init(context)
    return context.contentResolver.openInputStream(uri)?.use { stream ->
      PDDocument.load(stream).use { document ->
        if (document.isEncrypted) {
          throw IllegalStateException("Encrypted")
        }
        tidy(PDFTextStripper().getText(document))
      }
    } ?: throw IllegalStateException("No stream for $uri")
  }

  /** Where Lin's own documents live: its files, not the user's. */
  fun writtenDirectory(): File =
    File(context.filesDir, WRITTEN).also { if (!it.exists()) it.mkdirs() }

  private companion object {
    const val TAG = "LinDocuments"
    const val DOCX_BODY = "word/document.xml"
    const val WRITTEN = "documents"
  }
}
