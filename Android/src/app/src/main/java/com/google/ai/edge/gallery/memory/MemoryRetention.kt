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

/**
 * How long Lin keeps a thing in mind, and which of the things it keeps are worth saying out loud.
 *
 * Two different limits meet here and they pull in opposite directions.
 *
 * The vault can hold a great deal - it is a few hundred kilobytes of proto and some files. The
 * system prompt cannot. Every note put in front of the model is charged against the same few
 * thousand tokens as the tool schemas and the conversation itself, so the prompt gets a small,
 * deliberately chosen handful and everything else waits behind a tool. An assistant that recites
 * everything it knows about you on every turn has no room left to think.
 *
 * The second limit is time. A note that was true in March and is not true now is worse than no
 * note: it is repeated with complete confidence and nothing in the conversation contradicts it.
 * So warmth decays. Anything not said, implied or acted on within its window cools into the
 * archive - not deleted, because being asked about it is itself evidence it still matters, and a
 * cold note warms again when it is found.
 *
 * The windows below are all at least three weeks, which is the floor this was built to. They
 * differ above that floor because the kinds differ: where somebody lives does not go stale at the
 * same rate as what they were working on last month.
 */
object MemoryRetention {

  /** The shortest any note stays warm, whatever its kind. Three weeks. */
  const val MINIMUM_WARM_DAYS = 21

  private const val DAY_MILLIS = 24L * 60 * 60 * 1000

  /**
   * How long each kind stays warm without being confirmed again.
   *
   * Read these as "how long before it is worth doubting". A name does not stop being a name;
   * something mentioned once in passing usually stops mattering within the month.
   */
  private val WARM_DAYS: Map<NoteKind, Int> =
    mapOf(
      NoteKind.NOTE_KIND_IDENTITY to 365,
      NoteKind.NOTE_KIND_RELATIONSHIP to 180,
      NoteKind.NOTE_KIND_PREFERENCE to 120,
      NoteKind.NOTE_KIND_ROUTINE to 90,
      NoteKind.NOTE_KIND_PROJECT to 45,
      NoteKind.NOTE_KIND_CONTEXT to MINIMUM_WARM_DAYS,
    )

  /** The window for a kind, never below the floor. */
  fun warmDays(kind: NoteKind): Int =
    maxOf(WARM_DAYS[kind] ?: MINIMUM_WARM_DAYS, MINIMUM_WARM_DAYS)

  /**
   * Whether [note] still belongs in warm memory at [nowMillis].
   *
   * Three things keep a note warm regardless of age. The user wrote it themselves, so it is not
   * the model's inference to expire. They pinned it. Or it has been confirmed in several separate
   * conversations, which is the difference between something said once and something true - each
   * confirmation beyond the first adds a week.
   */
  fun isWarm(note: MemoryNote, nowMillis: Long): Boolean {
    if (note.userWritten || note.pinned) return true
    val bonusDays = (note.reinforcementCount - 1).coerceAtLeast(0) * 7
    val windowMillis = (warmDays(note.kind) + bonusDays) * DAY_MILLIS
    val since = nowMillis - note.lastConfirmedAtMillis
    // A note from the future is a clock that moved, not a note that is stale.
    if (since < 0) return true
    return since <= windowMillis
  }

  /**
   * The vault with every note's `cold` flag brought up to date at [nowMillis].
   *
   * Cooling is a flag rather than a deletion, and warming is its exact inverse, so a note that is
   * found and used again rejoins warm memory rather than being learned a second time from
   * scratch.
   */
  fun sweep(notes: List<MemoryNote>, nowMillis: Long): List<MemoryNote> =
    notes.map { note ->
      val shouldBeCold = !isWarm(note, nowMillis)
      if (note.cold == shouldBeCold) note else note.toBuilder().setCold(shouldBeCold).build()
    }

  /**
   * How many characters of the system prompt the memory section may occupy.
   *
   * Halved from 1,400 after the fixed overhead - prompt plus forty-odd tool schemas - came to
   * 4,230 tokens against a 4,000 limit and killed the first message of every conversation. This
   * was not the largest part of that, but it was the part added most recently and the easiest to
   * give back. Seven hundred characters is still a dozen notes, and everything else is a
   * searchMemory call away.
   */
  const val MAX_PROMPT_CHARS = 700

  /** And how many notes, however short they are. A long list reads as noise whatever it costs. */
  const val MAX_PROMPT_NOTES = 12

  /**
   * Which notes go in front of the model this turn, in the order they should be read.
   *
   * The ranking is deliberate rather than merely recent. What the user wrote themselves outranks
   * what the model inferred, because one is a statement and the other is a guess. Identity
   * outranks passing context, because getting somebody's name wrong is a different class of error
   * from forgetting what they mentioned on Tuesday. Among equals, what has been confirmed most
   * often wins, and then what was confirmed most recently.
   *
   * Cold notes are never selected. They are reachable, by tool, when something asks for them.
   */
  fun forPrompt(
    notes: List<MemoryNote>,
    nowMillis: Long,
    maxChars: Int = MAX_PROMPT_CHARS,
    maxNotes: Int = MAX_PROMPT_NOTES,
  ): List<MemoryNote> {
    val warm = notes.filter { isWarm(it, nowMillis) && it.text.isNotBlank() }
    val ranked =
      warm.sortedWith(
        compareByDescending<MemoryNote> { it.pinned }
          .thenByDescending { it.userWritten }
          .thenBy { kindRank(it.kind) }
          .thenByDescending { it.reinforcementCount }
          .thenByDescending { it.lastConfirmedAtMillis }
          .thenBy { it.id }
      )

    val chosen = mutableListOf<MemoryNote>()
    var used = 0
    for (note in ranked) {
      if (chosen.size >= maxNotes) break
      // One line each, so the cost of a note is its text plus the bullet and the newline.
      val cost = note.text.length + 3
      if (used + cost > maxChars) {
        // Keep going rather than stopping: a short high-ranking note after a long one still fits,
        // and dropping it would make the selection depend on length more than on worth.
        continue
      }
      chosen.add(note)
      used += cost
    }
    return chosen
  }

  /** Lower sorts earlier. */
  private fun kindRank(kind: NoteKind): Int =
    when (kind) {
      NoteKind.NOTE_KIND_IDENTITY -> 0
      NoteKind.NOTE_KIND_RELATIONSHIP -> 1
      NoteKind.NOTE_KIND_PREFERENCE -> 2
      NoteKind.NOTE_KIND_ROUTINE -> 3
      NoteKind.NOTE_KIND_PROJECT -> 4
      NoteKind.NOTE_KIND_CONTEXT -> 5
      else -> 6
    }

  /** The most notes the vault keeps at all. Beyond this the coldest are dropped for good. */
  const val MAX_NOTES = 600

  /** The most conversation digests kept in the index. Their transcripts stay on disk regardless. */
  const val MAX_DIGESTS = 400

  /**
   * [notes] cut down to [MAX_NOTES], dropping the least worth keeping.
   *
   * Only ever drops cold notes, and the oldest-confirmed of those first. A warm note is never
   * evicted to make room - if warm memory alone exceeds the cap, that is a signal the extraction
   * is too eager, and silently discarding what the user told you is the wrong way to find out.
   */
  fun capped(notes: List<MemoryNote>, nowMillis: Long, max: Int = MAX_NOTES): List<MemoryNote> {
    if (notes.size <= max) return notes
    val warm = notes.filter { isWarm(it, nowMillis) }
    val cold = notes.filterNot { isWarm(it, nowMillis) }
    val room = (max - warm.size).coerceAtLeast(0)
    val keptCold =
      cold.sortedByDescending { it.lastConfirmedAtMillis }.take(room)
    // Back into the original order, so the vault does not reshuffle itself on every save.
    val kept = (warm + keptCold).associateBy { it.id }
    return notes.filter { kept.containsKey(it.id) }
  }

  /** Renders the selected notes as the memory section of the prompt. */
  fun format(notes: List<MemoryNote>): String =
    notes.joinToString("\n") { "- ${it.text}" }
}
