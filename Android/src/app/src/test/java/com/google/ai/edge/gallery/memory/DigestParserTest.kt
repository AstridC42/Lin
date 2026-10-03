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

import com.google.ai.edge.gallery.proto.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading back whatever the model wrote about a conversation.
 *
 * Nobody is watching when this runs. A small model on a phone, asked for labelled lines, will
 * variously wrap the reply in a code fence, bullet the labels, answer in a different case, add
 * prose around the edges, or helpfully write "NOTE: none" - and every one of those has to come
 * out as either a usable digest or an honest nothing. A crash here is a background worker that
 * stops remembering; a mis-parse is a thing Lin believes about somebody for ever.
 */
class DigestParserTest {

  @Test
  fun readsTheFormatItAsksFor() {
    val parsed =
      DigestParser.parse(
        """
        TITLE: Train to Haifa
        SUMMARY: Worked out the route and set an alarm for 6am.
        TOPICS: travel, trains, alarms
        NOTE [routine]: takes the 7:15 train to work
        NOTE [identity]: lives in Haifa
        """
          .trimIndent()
      )
    assertNotNull(parsed)
    assertEquals("Train to Haifa", parsed!!.title)
    assertEquals("Worked out the route and set an alarm for 6am.", parsed.summary)
    assertEquals(listOf("travel", "trains", "alarms"), parsed.topics)
    assertEquals(2, parsed.notes.size)
    assertEquals(NoteKind.NOTE_KIND_ROUTINE, parsed.notes[0].kind)
    assertEquals(NoteKind.NOTE_KIND_IDENTITY, parsed.notes[1].kind)
  }

  /** Several models wrap everything in a fence whatever they are told. */
  @Test
  fun seesThroughACodeFence() {
    val parsed = DigestParser.parse("```\nTITLE: Groceries\nSUMMARY: A shopping list.\n```")
    assertEquals("Groceries", parsed?.title)
  }

  @Test
  fun toleratesBulletsAndCasing() {
    val parsed =
      DigestParser.parse(
        """
        - **Title:** Groceries
        * summary: A shopping list.
        > note [preference]: prefers oat milk
        """
          .trimIndent()
      )
    assertEquals("Groceries", parsed?.title)
    assertEquals("A shopping list.", parsed?.summary)
    assertEquals(1, parsed?.notes?.size)
    assertEquals(NoteKind.NOTE_KIND_PREFERENCE, parsed?.notes?.first()?.kind)
  }

  /**
   * Told that none is a good answer, a model writes it down as a note. Stored, "none" becomes
   * something Lin believes about the user.
   */
  @Test
  fun doesNotStoreARefusalAsAFact() {
    val parsed =
      DigestParser.parse(
        """
        TITLE: Small talk
        SUMMARY: Nothing much.
        NOTE: none
        NOTE: N/A
        NOTE: nothing
        """
          .trimIndent()
      )
    assertNotNull(parsed)
    assertTrue("a refusal is not a fact", parsed!!.notes.isEmpty())
  }

  /** A conversation that taught nothing is the common case, and is perfectly good. */
  @Test
  fun acceptsADigestWithNoNotes() {
    val parsed = DigestParser.parse("TITLE: Weather\nSUMMARY: Checked the forecast.")
    assertNotNull(parsed)
    assertTrue(parsed!!.notes.isEmpty())
  }

  /** Nothing usable has to come back as nothing, not as an empty digest that looks real. */
  @Test
  fun givesNothingBackForNothing() {
    assertNull(DigestParser.parse(""))
    assertNull(DigestParser.parse("I'm sorry, I can't help with that."))
    assertNull(DigestParser.parse("TITLE:\nSUMMARY:   "))
  }

  /** A label with no content must never become the content. */
  @Test
  fun neverTurnsALabelIntoAFact() {
    val parsed = DigestParser.parse("TITLE: Something\nNOTE:")
    assertNotNull(parsed)
    assertTrue(parsed!!.notes.none { it.text.contains("NOTE", ignoreCase = true) })
  }

  /** One conversation cannot fill the vault, however enthusiastic the model is. */
  @Test
  fun capsHowMuchOneConversationContributes() {
    val many = (1..40).joinToString("\n") { "NOTE [context]: fact number $it" }
    val parsed = DigestParser.parse("TITLE: Long\n$many")
    assertEquals(DigestParser.MAX_NOTES_PER_CONVERSATION, parsed?.notes?.size)
  }

  @Test
  fun capsTheTitleAndSummary() {
    val parsed =
      DigestParser.parse("TITLE: ${"word ".repeat(100)}\nSUMMARY: ${"word ".repeat(500)}")
    assertTrue(parsed!!.title.length <= DigestParser.MAX_TITLE_CHARS + 3)
    assertTrue(parsed.summary.length <= DigestParser.MAX_SUMMARY_CHARS + 3)
  }

  /** An unknown kind is context rather than a parse failure that loses the note. */
  @Test
  fun treatsAnUnknownKindAsContext() {
    val parsed = DigestParser.parse("NOTE [sentiment]: likes short answers")
    assertEquals(NoteKind.NOTE_KIND_CONTEXT, parsed?.notes?.first()?.kind)
  }

  /** The instruction not to invent is the load-bearing one, so it must actually be in there. */
  @Test
  fun theInstructionsSayNotToGuess() {
    val prompt = DigestParser.prompt("some transcript")
    assertTrue(prompt.contains("Never guess"))
    assertTrue(prompt.contains("some transcript"))
  }

  // -------------------------------------------------------- extractive

  /** Every conversation gets an entry, even with no model loaded to write one. */
  @Test
  fun extractiveDigestNamesTheConversationFromWhatWasAsked() {
    val parsed =
      ExtractiveDigest.from(
        userMessages = listOf("what time is the train to Haifa", "set an alarm for six"),
        modelMessages = listOf("The 7:15.", "Done."),
      )
    assertNotNull(parsed)
    assertEquals("what time is the train to Haifa", parsed!!.title)
    assertTrue(parsed.summary.contains("Not yet summarised"))
    // Never notes: guessing facts about a person by reading a transcript mechanically is how
    // "the user is interested in cancel" gets remembered for ever.
    assertTrue(parsed.notes.isEmpty())
  }

  @Test
  fun extractiveDigestGivesNothingBackForAnEmptyConversation() {
    assertNull(ExtractiveDigest.from(emptyList(), emptyList()))
    assertNull(ExtractiveDigest.from(listOf("   ", ""), listOf("hello")))
  }

  @Test
  fun transcriptTrimmingKeepsBothEnds() {
    val transcript = "START" + "x".repeat(10_000) + "END"
    val trimmed = ConversationArchive.forDigesting(transcript, maxChars = 500)
    assertTrue(trimmed.length <= 600)
    assertTrue("the subject is stated at the start", trimmed.startsWith("START"))
    assertTrue("the conclusion is at the finish", trimmed.endsWith("END"))
  }

  /** A session id is not ours to trust as a filename. */
  @Test
  fun archiveFileNamesAreSafe() {
    assertEquals("abc-123.md", ConversationArchive.fileNameFor("abc-123"))
    assertFalse(ConversationArchive.fileNameFor("../../etc/passwd").contains("/"))
    assertFalse(ConversationArchive.fileNameFor("..").contains(".."))
    assertEquals("conversation.md", ConversationArchive.fileNameFor(""))
  }
}
