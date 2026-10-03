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

import com.google.ai.edge.gallery.proto.MemoryNote
import com.google.ai.edge.gallery.proto.NoteKind

/** A note the model proposed, before it is known whether the vault already holds it. */
data class CandidateNote(val text: String, val kind: NoteKind)

/**
 * Folding what was just learned into what was already known.
 *
 * This runs after every conversation, unattended, which is what makes it worth being careful
 * about. The model is asked the same kind of question each time and will often answer the same
 * way, so without this the vault fills with ten phrasings of "takes the train" and the prompt
 * budget goes on saying one thing ten times.
 *
 * The other direction matters more. Overwriting a note because a new one looks similar would let
 * a single confused turn quietly replace something the user said themselves. So merging only ever
 * *reinforces* - it moves the clock forward and raises the count. Replacing the text of an
 * existing note is reserved for the user, through the memory screen or by saying so.
 */
object NoteMerge {

  /** The most source conversations kept per note. Enough to trace it; not an audit log. */
  const val MAX_SOURCES = 8

  /** A note longer than this is a paragraph, and a paragraph is not a note. */
  const val MAX_NOTE_CHARS = 180

  /**
   * How alike two notes must read to count as the same one.
   *
   * Measured as the share of significant words they have in common. Set by what the failure
   * looks like in each direction: too low and two genuinely different facts about the same
   * person collapse into one, which loses information silently; too high and the vault fills
   * with rephrasings, which merely wastes budget. Losing information is worse, so this errs high.
   */
  const val SAME_NOTE_OVERLAP = 0.8

  /** Words carrying no meaning for the comparison. */
  private val NOISE =
    setOf(
      "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "he", "her",
      "his", "in", "is", "it", "its", "of", "on", "or", "she", "that", "the", "their", "them",
      "they", "this", "to", "user", "was", "were", "with",
    )

  private val WORD = Regex("[^\\p{L}\\p{N}]+")

  /** The significant words of [text], lowercased. */
  internal fun significantWords(text: String): Set<String> =
    text.lowercase().split(WORD).filter { it.isNotBlank() && it !in NOISE }.toSet()

  /** Whether [a] and [b] say the same thing. */
  internal fun saysTheSameThing(a: String, b: String): Boolean {
    val left = significantWords(a)
    val right = significantWords(b)
    if (left.isEmpty() || right.isEmpty()) return false
    val shared = left.intersect(right).size.toDouble()
    // Against the smaller side, so "takes the train" still matches "takes the train to work".
    return shared / minOf(left.size, right.size) >= SAME_NOTE_OVERLAP
  }

  /** [text] trimmed, collapsed and cut to length, or null when nothing is left. */
  internal fun tidy(text: String): String? {
    val collapsed = text.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ';')
    if (collapsed.length < 3) return null
    return if (collapsed.length <= MAX_NOTE_CHARS) collapsed
    else collapsed.take(MAX_NOTE_CHARS).substringBeforeLast(' ').ifBlank {
      collapsed.take(MAX_NOTE_CHARS)
    }
  }

  /**
   * Folds [candidates] from conversation [conversationId] into [existing].
   *
   * Returns the whole note list and the ids touched - the ids being what the conversation's
   * digest links to, so the vault can be read from either end.
   */
  fun merge(
    existing: List<MemoryNote>,
    candidates: List<CandidateNote>,
    conversationId: String,
    nowMillis: Long,
    newId: () -> String,
  ): MergeResult {
    val notes = existing.toMutableList()
    val touched = LinkedHashSet<String>()

    for (candidate in candidates) {
      val text = tidy(candidate.text) ?: continue
      val index = notes.indexOfFirst { saysTheSameThing(it.text, text) }
      if (index >= 0) {
        val current = notes[index]
        notes[index] = reinforce(current, conversationId, nowMillis)
        touched.add(current.id)
      } else {
        val note =
          MemoryNote.newBuilder()
            .setId(newId())
            .setText(text)
            .setKind(
              if (candidate.kind == NoteKind.NOTE_KIND_UNSPECIFIED) NoteKind.NOTE_KIND_CONTEXT
              else candidate.kind
            )
            .setCreatedAtMillis(nowMillis)
            .setLastConfirmedAtMillis(nowMillis)
            .setReinforcementCount(1)
            .addSourceConversationId(conversationId)
            .build()
        notes.add(note)
        touched.add(note.id)
      }
    }
    return MergeResult(notes = notes, touchedNoteIds = touched.toList())
  }

  /** The same note, confirmed again now. */
  internal fun reinforce(note: MemoryNote, conversationId: String, nowMillis: Long): MemoryNote {
    val builder = note.toBuilder().setLastConfirmedAtMillis(nowMillis).setCold(false)
    // A conversation that mentions something twice has still only confirmed it once.
    if (!note.sourceConversationIdList.contains(conversationId)) {
      builder.reinforcementCount = note.reinforcementCount + 1
      val sources = (note.sourceConversationIdList + conversationId).takeLast(MAX_SOURCES)
      builder.clearSourceConversationId().addAllSourceConversationId(sources)
    }
    return builder.build()
  }

  data class MergeResult(val notes: List<MemoryNote>, val touchedNoteIds: List<String>)
}
