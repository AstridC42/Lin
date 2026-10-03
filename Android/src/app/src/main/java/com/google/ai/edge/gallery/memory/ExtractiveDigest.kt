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

/**
 * A digest written without the model.
 *
 * The digester needs the language model, and the language model is not always there: it is
 * gigabytes that Android reclaims the moment nothing is visible, and the whole point of this
 * running in the background is that it runs when the user has walked away. Waiting for a loaded
 * model would mean conversations piling up undigested, and the pile is exactly what gets lost
 * when storage is cleared or the app is reinstalled.
 *
 * So every conversation gets an entry immediately, assembled from the transcript itself, and the
 * digest is marked `extractive` so a later pass with a model loaded can replace it with a real
 * summary. Nothing is ever only in the queue.
 *
 * Deliberately no notes. Pulling facts about a person out of a transcript by counting words is
 * the kind of thing that produces "the user is interested in cancel" from somebody cancelling an
 * alarm, and a wrong note is repeated with confidence until somebody corrects it. Summaries can
 * be rough. Notes cannot.
 */
object ExtractiveDigest {

  const val MAX_TITLE_CHARS = 70
  const val MAX_SUMMARY_CHARS = 400

  /**
   * Builds a placeholder digest from what was said.
   *
   * @param userMessages the user's turns, in order.
   * @param modelMessages the replies, in order.
   */
  fun from(userMessages: List<String>, modelMessages: List<String>): ParsedDigest? {
    val asked = userMessages.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
    if (asked.isEmpty()) return null

    val title = cap(asked.first(), MAX_TITLE_CHARS)

    // What the user asked, in their own words, rather than an attempt at prose. A list of the
    // actual questions reads honestly as a placeholder; invented narration would not.
    val summary = buildString {
      append("Not yet summarised. ")
      append(
        if (asked.size == 1) "The user asked: " else "The user asked about: "
      )
      append(asked.take(4).joinToString("; ") { cap(it, 90) })
      if (asked.size > 4) append("; and ${asked.size - 4} more")
      append(".")
      val replies = modelMessages.count { it.isNotBlank() }
      if (replies > 0) append(" $replies ${if (replies == 1) "reply" else "replies"}.")
    }

    return ParsedDigest(
      title = title,
      summary = cap(summary, MAX_SUMMARY_CHARS),
      topics = emptyList(),
      notes = emptyList(),
    )
  }

  private fun cap(value: String, max: Int): String {
    val collapsed = value.replace(Regex("\\s+"), " ").trim()
    if (collapsed.length <= max) return collapsed
    return collapsed.take(max).substringBeforeLast(' ').ifBlank { collapsed.take(max) } + "..."
  }
}
