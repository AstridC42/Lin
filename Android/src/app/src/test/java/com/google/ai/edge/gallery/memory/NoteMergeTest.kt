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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding what was just learned into what was already known.
 *
 * This runs unattended after every conversation, which is the whole reason it needs holding
 * down. The digester is asked the same question each time and answers much the same way, so
 * without deduplication the vault fills with ten phrasings of one fact and the prompt budget
 * goes on saying it ten times.
 *
 * The opposite error is worse and quieter: collapse two genuinely different facts into one and
 * the second is gone, with nothing to show it ever arrived.
 */
class NoteMergeTest {

  private val now = 1_700_000_000_000L
  private var counter = 0

  private fun ids(): () -> String = { "id-${counter++}" }

  private fun existing(text: String, id: String = "old", sources: List<String> = listOf("c0")) =
    MemoryNote.newBuilder()
      .setId(id)
      .setText(text)
      .setKind(NoteKind.NOTE_KIND_CONTEXT)
      .setCreatedAtMillis(now - 1000)
      .setLastConfirmedAtMillis(now - 1000)
      .setReinforcementCount(1)
      .addAllSourceConversationId(sources)
      .build()

  @Test
  fun keepsSomethingNew() {
    val result =
      NoteMerge.merge(emptyList(), listOf(CandidateNote("takes the train", NoteKind.NOTE_KIND_ROUTINE)), "c1", now, ids())
    assertEquals(1, result.notes.size)
    assertEquals("takes the train", result.notes.first().text)
    assertEquals(NoteKind.NOTE_KIND_ROUTINE, result.notes.first().kind)
    assertEquals(listOf(result.notes.first().id), result.touchedNoteIds)
  }

  /** The same thing said differently is one fact, confirmed twice. */
  @Test
  fun reinforcesRatherThanDuplicating() {
    val result =
      NoteMerge.merge(
        listOf(existing("takes the train to work")),
        listOf(CandidateNote("takes the train", NoteKind.NOTE_KIND_ROUTINE)),
        "c1",
        now,
        ids(),
      )
    assertEquals(1, result.notes.size)
    assertEquals(2, result.notes.first().reinforcementCount)
    assertEquals(now, result.notes.first().lastConfirmedAtMillis)
    // Reinforcing never rewrites the wording: the existing note is the one that was agreed to.
    assertEquals("takes the train to work", result.notes.first().text)
  }

  /** Confirming the same thing twice inside one conversation is still one confirmation. */
  @Test
  fun doesNotInflateTheCountWithinOneConversation() {
    val result =
      NoteMerge.merge(
        listOf(existing("takes the train", sources = listOf("c1"))),
        listOf(
          CandidateNote("takes the train", NoteKind.NOTE_KIND_ROUTINE),
          CandidateNote("takes the train to work", NoteKind.NOTE_KIND_ROUTINE),
        ),
        "c1",
        now,
        ids(),
      )
    assertEquals(1, result.notes.size)
    assertEquals(1, result.notes.first().reinforcementCount)
  }

  /** Two facts about the same subject are two facts. */
  @Test
  fun keepsThingsThatActuallyDiffer() {
    val result =
      NoteMerge.merge(
        listOf(existing("allergic to peanuts")),
        listOf(CandidateNote("allergic to shellfish", NoteKind.NOTE_KIND_IDENTITY)),
        "c1",
        now,
        ids(),
      )
    assertEquals(2, result.notes.size)
  }

  @Test
  fun comparisonIgnoresPunctuationCaseAndFiller() {
    assertTrue(NoteMerge.saysTheSameThing("Takes the train.", "takes the train"))
    assertTrue(NoteMerge.saysTheSameThing("The user takes the train", "takes the train"))
    assertFalse(NoteMerge.saysTheSameThing("takes the train", "takes the bus"))
  }

  /** A note that reduces to nothing is not a note. */
  @Test
  fun refusesEmptyCandidates() {
    assertNull(NoteMerge.tidy("   "))
    assertNull(NoteMerge.tidy("a"))
    val result = NoteMerge.merge(emptyList(), listOf(CandidateNote("  ", NoteKind.NOTE_KIND_CONTEXT)), "c1", now, ids())
    assertTrue(result.notes.isEmpty())
  }

  /** A paragraph is not a note, and a note that long would crowd out the conversation. */
  @Test
  fun trimsSomethingThatRunsOn() {
    val long = "the user " + "word ".repeat(200)
    val tidied = NoteMerge.tidy(long)!!
    assertTrue(tidied.length <= NoteMerge.MAX_NOTE_CHARS)
  }

  /** Sources are for tracing a note back, not for keeping an audit log of every mention. */
  @Test
  fun capsHowManySourcesAreKept() {
    var notes = listOf(existing("takes the train", sources = listOf("c0")))
    for (i in 1..20) {
      notes = NoteMerge.merge(notes, listOf(CandidateNote("takes the train", NoteKind.NOTE_KIND_ROUTINE)), "c$i", now, ids()).notes
    }
    assertTrue(notes.first().sourceConversationIdCount <= NoteMerge.MAX_SOURCES)
    // The most recent conversation is the one worth keeping.
    assertTrue(notes.first().sourceConversationIdList.contains("c20"))
  }

  /** An unlabelled note is context rather than an unspecified kind that sorts unpredictably. */
  @Test
  fun defaultsAnUnlabelledKind() {
    val result =
      NoteMerge.merge(emptyList(), listOf(CandidateNote("likes short answers", NoteKind.NOTE_KIND_UNSPECIFIED)), "c1", now, ids())
    assertEquals(NoteKind.NOTE_KIND_CONTEXT, result.notes.first().kind)
  }

  /** Being confirmed again brings a note back from cold rather than leaving a duplicate behind. */
  @Test
  fun reinforcingWarmsAColdNote() {
    val cold = existing("takes the train").toBuilder().setCold(true).build()
    val result =
      NoteMerge.merge(listOf(cold), listOf(CandidateNote("takes the train", NoteKind.NOTE_KIND_ROUTINE)), "c9", now, ids())
    assertEquals(1, result.notes.size)
    assertFalse(result.notes.first().cold)
  }
}
