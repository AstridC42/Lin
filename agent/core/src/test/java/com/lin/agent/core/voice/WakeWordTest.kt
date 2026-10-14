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

package com.lin.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hearing "Hey Lin" in what a recogniser thought it heard.
 *
 * Two failures, pulling in opposite directions. Too strict and it never fires, because a
 * recogniser hands back "hey Lynn" and "hey line" for the same two syllables. Too loose and it
 * fires in somebody's pocket, which opens a microphone they did not ask to open - the worse of
 * the two by a long way.
 */
class WakeWordTest {

  // ---- what must wake it ----

  @Test
  fun thePhraseItselfWakesIt() {
    assertTrue(WakeWord.saidIn("hey lin"))
    assertTrue(WakeWord.saidIn("Hey Lin"))
    assertTrue(WakeWord.saidIn("Hey, Lin!"))
  }

  /** What a recogniser actually returns for a two-syllable name it does not know. */
  @Test
  fun theUsualMishearingsWakeIt() {
    for (heard in listOf("hey lynn", "hey line", "hey len", "hey linn", "hey lyn")) {
      assertTrue(heard, WakeWord.saidIn(heard))
    }
  }

  @Test
  fun thePhraseInsideASentenceWakesIt() {
    assertTrue(WakeWord.saidIn("okay so hey Lin are you there"))
  }

  /** A recogniser runs a half-known phrase together. */
  @Test
  fun thePhraseRunTogetherWakesIt() {
    assertTrue(WakeWord.saidIn("heylin"))
    assertTrue(WakeWord.saidIn("heylynn"))
  }

  // ---- what must not ----

  /**
   * The worse failure of the two.
   *
   * A wake word that fires on ordinary speech opens a microphone nobody asked to open, in a
   * pocket, and the person finds out later or not at all.
   */
  @Test
  fun halfThePhraseDoesNotWakeIt() {
    assertFalse(WakeWord.saidIn("lin"))
    assertFalse(WakeWord.saidIn("hey"))
    assertFalse(WakeWord.saidIn("hey there"))
    assertFalse(WakeWord.saidIn("tell Lynn I said hello"))
  }

  @Test
  fun ordinarySpeechDoesNotWakeIt() {
    for (heard in
      listOf(
        "I was feeling fine",
        "the deadline is Friday",
        "hang on a minute",
        "they went online yesterday",
        "",
        "   ",
      )) {
      assertFalse(heard, WakeWord.saidIn(heard))
    }
  }

  /** Both halves have to be there, in order. */
  @Test
  fun thePhraseBackwardsDoesNotWakeIt() {
    assertFalse(WakeWord.saidIn("Lin, hey"))
  }

  /** Two letters is one edit away from half the language. */
  @Test
  fun somethingTooShortDoesNotWakeIt() {
    assertFalse(WakeWord.saidIn("hey li"))
    assertFalse(WakeWord.saidIn("he li"))
  }

  // ---- carrying the question through ----

  /**
   * "Hey Lin, what's the time" should ask the question.
   *
   * Otherwise the wake word only opens the microphone and the person says it all again, which
   * makes the feature slower than reaching for the phone.
   */
  @Test
  fun whatFollowsThePhraseIsTheQuestion() {
    assertEquals("what's the time", WakeWord.afterWake("hey Lin what's the time"))
    assertEquals("turn the music down", WakeWord.afterWake("Hey, Lin, turn the music down"))
  }

  @Test
  fun thePhraseOnItsOwnCarriesNoQuestion() {
    assertEquals("", WakeWord.afterWake("hey Lin"))
    assertEquals("", WakeWord.afterWake("hey lynn"))
  }

  @Test
  fun nothingIsCarriedWhenThePhraseWasNotSaid() {
    assertEquals("", WakeWord.afterWake("what's the time"))
  }
}
