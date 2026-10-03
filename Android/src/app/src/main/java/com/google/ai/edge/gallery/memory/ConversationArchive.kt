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

package com.google.ai.edge.gallery.memory

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "LinArchive"

/**
 * Cold storage: the whole of every conversation, on disk, out of the way.
 *
 * The vault index holds a paragraph per conversation and is read on every launch. The transcripts
 * are the opposite - large, rarely wanted, and worth keeping in full precisely because a summary
 * is lossy and nobody knows in advance which detail will be asked for later. So they live in
 * files rather than in the settings store, which is rewritten whole on every change and would
 * grow until a launch took seconds.
 *
 * Plain Markdown rather than anything compressed or binary. These files outlive the code that
 * wrote them, they are the only copy of what was actually said, and a format a person can read
 * with any text editor is worth more here than the few megabytes compression would save on a
 * device that already stores multi-gigabyte models.
 */
class ConversationArchive(private val context: Context) {

  /** Everything archived lives under one directory, so it can be cleared in one action. */
  private val root: File
    get() = File(context.filesDir, "memory/archive")

  /**
   * Writes [session] out and returns the path to store in its digest, or null if it could not be
   * written.
   *
   * A failure here must not stop the digest being saved: a summary with no transcript behind it
   * is worth much more than no memory of the conversation at all.
   */
  fun write(session: ChatSessionProto): String? {
    val name = fileNameFor(session.sessionId)
    return try {
      root.mkdirs()
      val file = File(root, name)
      file.writeText(render(session))
      name
    } catch (e: Exception) {
      Log.e(TAG, "Could not archive ${session.sessionId}", e)
      null
    }
  }

  /** Reads back a transcript by the path stored in its digest, or null when it has gone. */
  fun read(archivePath: String): String? {
    if (archivePath.isBlank()) return null
    return try {
      val file = File(root, archivePath)
      // The path comes out of the vault rather than from outside, but a stored path is still a
      // path, and one containing `..` would read any file the app can.
      if (!file.canonicalPath.startsWith(root.canonicalPath)) {
        Log.w(TAG, "Refusing to read outside the archive: $archivePath")
        return null
      }
      if (file.exists()) file.readText() else null
    } catch (e: Exception) {
      Log.e(TAG, "Could not read $archivePath", e)
      null
    }
  }

  /** Removes one transcript, for when its digest is deleted. */
  fun delete(archivePath: String) {
    if (archivePath.isBlank()) return
    try {
      val file = File(root, archivePath)
      if (file.canonicalPath.startsWith(root.canonicalPath)) file.delete()
    } catch (e: Exception) {
      Log.w(TAG, "Could not delete $archivePath", e)
    }
  }

  /** Everything, for the "forget all of it" action. */
  fun deleteAll() {
    try {
      root.deleteRecursively()
    } catch (e: Exception) {
      Log.w(TAG, "Could not clear the archive", e)
    }
  }

  /** Total bytes on disk, so the memory screen can say what this costs. */
  fun sizeInBytes(): Long =
    try {
      root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
      0L
    }

  companion object {
    /** A session id is not ours to trust as a filename. */
    internal fun fileNameFor(sessionId: String): String {
      val safe = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
      return (safe.ifBlank { "conversation" }) + ".md"
    }

    /** The transcript as it is stored, and as the model is shown it when digesting. */
    fun render(session: ChatSessionProto): String {
      val stamp =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.timestampMs))
      return buildString {
        appendLine("# ${session.title.ifBlank { "Conversation" }}")
        appendLine()
        appendLine("- When: $stamp")
        appendLine("- Model: ${session.originalModel.ifBlank { "unknown" }}")
        appendLine("- Id: ${session.sessionId}")
        appendLine()
        for (message in session.messagesList) {
          val who =
            when (message.side) {
              ChatSideProto.CHAT_SIDE_USER -> "User"
              ChatSideProto.CHAT_SIDE_MODEL -> "Lin"
              else -> "System"
            }
          val text = message.content.trim()
          if (text.isEmpty()) continue
          appendLine("**$who:** $text")
          appendLine()
        }
      }
    }

    /** How much of a transcript the digester is shown. */
    const val MAX_DIGEST_INPUT_CHARS = 6_000

    /**
     * The transcript trimmed to what the digester can read.
     *
     * Keeps the beginning and the end rather than the first N characters. A long conversation
     * states its subject near the start and reaches its conclusion at the finish; the middle is
     * where the repetition lives, so the middle is what goes.
     */
    fun forDigesting(transcript: String, maxChars: Int = MAX_DIGEST_INPUT_CHARS): String {
      if (transcript.length <= maxChars) return transcript
      val head = (maxChars * 2) / 3
      val tail = maxChars - head
      return transcript.take(head).trimEnd() +
        "\n\n[...middle of the conversation left out...]\n\n" +
        transcript.takeLast(tail).trimStart()
    }
  }
}
