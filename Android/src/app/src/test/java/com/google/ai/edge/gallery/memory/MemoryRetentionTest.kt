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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Lin still knows after time passes, and what it says out loud.
 *
 * Both halves fail silently and in opposite directions, which is why they are pinned here rather
 * than checked by hand.
 *
 * Forget too eagerly and the thing the product exists for stops working, with no error anywhere:
 * the assistant simply stops knowing you, a few weeks in, and the only symptom is that it asks
 * again. Three weeks is the floor this was built to, so it is asserted as a floor rather than
 * left to the table of windows.
 *
 * And select too generously and every turn carries a recitation of everything known, out of the
 * same budget as the tools and the conversation - which does not look like a memory bug at all.
 * It looks like the model getting worse.
 */
class MemoryRetentionTest {

  private val now = 1_700_000_000_000L
  private val day = 24L * 60 * 60 * 1000

  private fun note(
    id: String,
    text: String = "takes the train",
    kind: NoteKind = NoteKind.NOTE_KIND_CONTEXT,
    confirmedDaysAgo: Long = 0,
    reinforcements: Int = 1,
    userWritten: Boolean = false,
    pinned: Boolean = false,
  ): MemoryNote =
    MemoryNote.newBuilder()
      .setId(id)
      .setText(text)
      .setKind(kind)
      .setCreatedAtMillis(now - confirmedDaysAgo * day)
      .setLastConfirmedAtMillis(now - confirmedDaysAgo * day)
      .setReinforcementCount(reinforcements)
      .setUserWritten(userWritten)
      .setPinned(pinned)
      .build()

  /** The promise the whole design rests on: nothing is forgotten inside three weeks. */
  @Test
  fun everythingSurvivesThreeWeeks() {
    for (kind in NoteKind.values()) {
      if (kind == NoteKind.UNRECOGNIZED) continue
      assertTrue(
        "a $kind note must still be warm after ${MemoryRetention.MINIMUM_WARM_DAYS} days",
        MemoryRetention.isWarm(note("n", kind = kind, confirmedDaysAgo = 21), now),
      )
    }
  }

  @Test
  fun passingContextCoolsOffEventually() {
    assertFalse(
      MemoryRetention.isWarm(
        note("n", kind = NoteKind.NOTE_KIND_CONTEXT, confirmedDaysAgo = 40),
        now,
      )
    )
  }

  /** Who somebody is does not go stale at the rate of what they mentioned on Tuesday. */
  @Test
  fun identityOutlastsContext() {
    val old = 200L
    assertTrue(
      MemoryRetention.isWarm(note("a", kind = NoteKind.NOTE_KIND_IDENTITY, confirmedDaysAgo = old), now)
    )
    assertFalse(
      MemoryRetention.isWarm(note("b", kind = NoteKind.NOTE_KIND_CONTEXT, confirmedDaysAgo = old), now)
    )
  }

  /** Something confirmed in several conversations is a different claim from something heard once. */
  @Test
  fun repetitionBuysTime() {
    val heardOnce = note("a", kind = NoteKind.NOTE_KIND_CONTEXT, confirmedDaysAgo = 30)
    val heardOften =
      note("b", kind = NoteKind.NOTE_KIND_CONTEXT, confirmedDaysAgo = 30, reinforcements = 4)
    assertFalse(MemoryRetention.isWarm(heardOnce, now))
    assertTrue(MemoryRetention.isWarm(heardOften, now))
  }

  /** What the user wrote is not the model's inference to expire. */
  @Test
  fun whatTheUserWroteNeverCools() {
    assertTrue(
      MemoryRetention.isWarm(note("a", confirmedDaysAgo = 9_999, userWritten = true), now)
    )
    assertTrue(MemoryRetention.isWarm(note("b", confirmedDaysAgo = 9_999, pinned = true), now))
  }

  /** A clock that moved backwards is not a reason to forget somebody's name. */
  @Test
  fun doesNotCoolOnAFutureTimestamp() {
    val fromTheFuture =
      note("a", confirmedDaysAgo = 0).toBuilder().setLastConfirmedAtMillis(now + 10 * day).build()
    assertTrue(MemoryRetention.isWarm(fromTheFuture, now))
  }

  /** Cooling is reversible, which is what makes it safe to do unattended. */
  @Test
  fun sweepMarksBothDirections() {
    val stale = note("a", confirmedDaysAgo = 60).toBuilder().setCold(false).build()
    val revived = note("b", confirmedDaysAgo = 1).toBuilder().setCold(true).build()
    val swept = MemoryRetention.sweep(listOf(stale, revived), now)
    assertTrue(swept.first { it.id == "a" }.cold)
    assertFalse(swept.first { it.id == "b" }.cold)
  }

  // ----------------------------------------------------------- selection

  @Test
  fun theSelectionStaysInsideItsBudget() {
    val many = (1..200).map { note("n$it", text = "fact number $it about the user and their life") }
    val chosen = MemoryRetention.forPrompt(many, now)
    assertTrue("at most ${MemoryRetention.MAX_PROMPT_NOTES}", chosen.size <= MemoryRetention.MAX_PROMPT_NOTES)
    assertTrue(
      "must fit the character budget, was ${MemoryRetention.format(chosen).length}",
      MemoryRetention.format(chosen).length <= MemoryRetention.MAX_PROMPT_CHARS,
    )
  }

  /** Getting a name wrong is a different class of mistake from forgetting a passing remark. */
  @Test
  fun leadsWithWhoTheyAre() {
    val chosen =
      MemoryRetention.forPrompt(
        listOf(
          note("ctx", text = "mentioned a film", kind = NoteKind.NOTE_KIND_CONTEXT),
          note("id", text = "lives in Haifa", kind = NoteKind.NOTE_KIND_IDENTITY),
        ),
        now,
      )
    assertEquals(listOf("id", "ctx"), chosen.map { it.id })
  }

  /** A statement outranks a guess, whatever the guess is about. */
  @Test
  fun whatTheUserSaidOutranksWhatTheModelInferred() {
    val chosen =
      MemoryRetention.forPrompt(
        listOf(
          note("inferred", text = "lives in Haifa", kind = NoteKind.NOTE_KIND_IDENTITY),
          note("stated", text = "do not call before ten", userWritten = true),
        ),
        now,
      )
    assertEquals("stated", chosen.first().id)
  }

  @Test
  fun neverSelectsWhatHasCooled() {
    val chosen =
      MemoryRetention.forPrompt(
        listOf(note("warm", confirmedDaysAgo = 1), note("cold", confirmedDaysAgo = 400)),
        now,
      )
    assertEquals(listOf("warm"), chosen.map { it.id })
  }

  /** A long note must not push out every short one that ranks below it. */
  @Test
  fun aLongNoteDoesNotBlockTheRest() {
    val chosen =
      MemoryRetention.forPrompt(
        listOf(
          note("long", text = "x".repeat(MemoryRetention.MAX_PROMPT_CHARS - 10)),
          note("short", text = "allergic to peanuts"),
        ),
        now,
        maxChars = 200,
      )
    assertEquals(listOf("short"), chosen.map { it.id })
  }

  // --------------------------------------------------------------- capping

  /** The cap only ever drops what has already cooled. */
  @Test
  fun cappingNeverEvictsSomethingStillWarm() {
    val warm = (1..5).map { note("w$it", confirmedDaysAgo = 1) }
    val cold = (1..20).map { note("c$it", confirmedDaysAgo = 300 + it.toLong()) }
    val capped = MemoryRetention.capped(warm + cold, now, max = 10)
    assertEquals(10, capped.size)
    assertTrue("every warm note survives", warm.all { w -> capped.any { it.id == w.id } })
  }

  /** And when warm memory alone exceeds the cap, nothing is thrown away silently. */
  @Test
  fun cappingKeepsEveryWarmNoteEvenPastTheCap() {
    val warm = (1..20).map { note("w$it", confirmedDaysAgo = 1) }
    val capped = MemoryRetention.capped(warm, now, max = 10)
    assertEquals(20, capped.size)
  }

  @Test
  fun formatsOneLinePerNote() {
    assertEquals(
      "- lives in Haifa\n- takes the train",
      MemoryRetention.format(listOf(note("a", text = "lives in Haifa"), note("b"))),
    )
  }
}
