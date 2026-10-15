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
 * When Lin listens, when it speaks, and what it says out loud.
 *
 * The microphone rules get the most attention here. An assistant that can open the microphone
 * is one that has to be obviously predictable about when it does - "it only listens because
 * somebody asked it to" is a claim that should be a test rather than a sentence in a privacy
 * policy.
 */
class VoicePolicyTest {

  // ---- when the microphone opens ----

  /** Summoned by the power button: there is no keyboard in that gesture. */
  @Test
  fun beingSummonedAsTheAssistantStartsListening() {
    assertTrue(shouldListenOnLaunch(Launch.ASSIST, microphoneGranted = true))
  }

  /**
   * Opening the app normally never opens the microphone.
   *
   * The rule that matters. An app that starts recording because it came to the front is the
   * behaviour people are right to be suspicious of, and no setting turns this on.
   */
  @Test
  fun openingTheAppNormallyNeverStartsListening() {
    assertFalse(shouldListenOnLaunch(Launch.NORMAL, microphoneGranted = true))
    assertFalse(shouldListenOnLaunch(Launch.NORMAL, microphoneGranted = false))
  }

  @Test
  fun withoutTheMicrophonePermissionNothingListens() {
    assertFalse(shouldListenOnLaunch(Launch.ASSIST, microphoneGranted = false))
  }

  // ---- when Lin speaks ----

  @Test
  fun aSpokenQuestionGetsASpokenAnswer() {
    assertTrue(shouldSpeak(SpeakWhen.WHEN_SPOKEN_TO, asked = Asked.SPOKEN))
  }

  /**
   * A typed question is answered in writing even with spoken replies on.
   *
   * Otherwise somebody typing in a quiet room is answered out loud because of a switch they set
   * last week. The mode follows how the person is using it now.
   */
  @Test
  fun aTypedQuestionIsNotAnsweredOutLoud() {
    assertFalse(shouldSpeak(SpeakWhen.WHEN_SPOKEN_TO, asked = Asked.TYPED))
  }

  @Test
  fun withSpokenRepliesOffNothingIsSpoken() {
    assertFalse(shouldSpeak(SpeakWhen.NEVER, asked = Asked.SPOKEN))
    assertFalse(shouldSpeak(SpeakWhen.NEVER, asked = Asked.TYPED))
  }

  // ---- what gets read out ----

  @Test
  fun emphasisMarkersAreNotReadAloud() {
    assertEquals("That is important.", forSpeaking("That is **important**."))
    assertEquals("Really.", forSpeaking("_Really_."))
  }

  @Test
  fun aBulletedListBecomesSentences() {
    val spoken = forSpeaking("Options:\n- call them\n- send a text")
    assertEquals("Options: call them. send a text.", spoken)
  }

  @Test
  fun aNumberedListLosesItsNumbering() {
    assertEquals("Send a message. Ask again.", forSpeaking("1. Send a message\n2) Ask again"))
  }

  @Test
  fun headingsAreReadAsPlainLines() {
    assertEquals("The plan. Do this.", forSpeaking("## The plan\nDo this."))
  }

  @Test
  fun codeTicksComeOff() {
    assertEquals("Set OLLAMA_HOST to 0.0.0.0.", forSpeaking("Set `OLLAMA_HOST` to 0.0.0.0."))
  }

  /** Existing punctuation is left alone rather than doubled. */
  @Test
  fun aSentenceThatAlreadyEndsProperlyIsNotGivenASecondFullStop() {
    assertEquals("Done. Is that right?", forSpeaking("Done.\nIs that right?"))
  }

  @Test
  fun blankLinesDisappearRatherThanBecomingPauses() {
    assertEquals("One. Two.", forSpeaking("One\n\n\nTwo"))
  }

  @Test
  fun plainTextIsLeftAsItIs() {
    assertEquals("Half past ten.", forSpeaking("Half past ten."))
  }

  @Test
  fun anEmptyReplyHasNothingToSay() {
    assertEquals("", forSpeaking(""))
    assertEquals("", forSpeaking("\n\n"))
  }
  @Test
  fun `a spoken question in a car is answered out loud whatever the setting says`() {
    // An answer only on screen is no answer to somebody driving, and they asked out loud.
    assertTrue(shouldSpeak(SpeakWhen.NEVER, asked = Asked.SPOKEN, inCar = true))
  }

  @Test
  fun `a typed question in a car is still answered in writing`() {
    // Either the car is stationary or somebody else is holding the phone. Neither wants it
    // talking, and "in a car" is not on its own a reason to speak.
    assertFalse(shouldSpeak(SpeakWhen.NEVER, asked = Asked.TYPED, inCar = true))
    assertFalse(shouldSpeak(SpeakWhen.WHEN_SPOKEN_TO, asked = Asked.TYPED, inCar = true))
  }

  @Test
  fun `out of a car nothing changes`() {
    assertTrue(shouldSpeak(SpeakWhen.WHEN_SPOKEN_TO, asked = Asked.SPOKEN, inCar = false))
    assertFalse(shouldSpeak(SpeakWhen.NEVER, asked = Asked.SPOKEN, inCar = false))
  }

  @Test
  fun `always means a typed question is answered out loud too`() {
    assertTrue(shouldSpeak(SpeakWhen.ALWAYS, asked = Asked.TYPED))
    assertTrue(shouldSpeak(SpeakWhen.ALWAYS, asked = Asked.SPOKEN))
  }

  @Test
  fun `an unreadable setting speaks when spoken to`() {
    // The default has to be the middle one: never would make a wake word pointless, and
    // always would have a phone in a quiet room start talking.
    assertEquals(SpeakWhen.WHEN_SPOKEN_TO, speakWhenNamed(null))
    assertEquals(SpeakWhen.WHEN_SPOKEN_TO, speakWhenNamed("whatever"))
    assertEquals(SpeakWhen.ALWAYS, speakWhenNamed("always"))
    assertEquals(SpeakWhen.NEVER, speakWhenNamed("NEVER"))
  }

}
