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

package com.lin.agent.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the open media sessions a command means, and how loud is too loud. */
class NowPlayingTest {

  private val music =
    Session("Music", "Teardrop", "Massive Attack", playing = true, activeAt = 300, can = setOf(MediaCommand.PAUSE, MediaCommand.NEXT))
  private val browser = Session("Browser", "A video", playing = false, activeAt = 200)
  private val podcast = Session("Podcasts", "Episode 12", playing = false, activeAt = 100)

  // ---- choosing, which is the whole difficulty ----

  /**
   * Three apps hold a session on an ordinary day.
   *
   * "Pause" reaching the podcast somebody stopped last Tuesday instead of the music playing
   * now is the difference between useful and infuriating.
   */
  @Test
  fun somethingPlayingWinsOverSomethingMoreRecentlyTouched() {
    val recentlyPaused = browser.copy(activeAt = 999)
    assertEquals(music, chooseSession(listOf(recentlyPaused, music, podcast)))
  }

  @Test
  fun amongSeveralPlayingTheMostRecentWins() {
    val other = Session("Browser", "A video", playing = true, activeAt = 400)
    assertEquals(other, chooseSession(listOf(music, other)))
  }

  /** With nothing playing, "play" should resume what was last listened to. */
  @Test
  fun withNothingPlayingTheMostRecentWins() {
    assertEquals(browser, chooseSession(listOf(browser, podcast)))
  }

  @Test
  fun nothingOpenIsNothingToControl() {
    assertNull(chooseSession(emptyList()))
  }

  // ---- what the model is told ----

  @Test
  fun whatIsPlayingNamesTheAppAndTheTrack() {
    val said = describeNowPlaying(listOf(music, podcast))
    assertTrue(said, said.contains("Music"))
    assertTrue(said, said.contains("Teardrop"))
    assertTrue(said, said.contains("Massive Attack"))
    assertTrue(said, said.startsWith("Playing"))
  }

  @Test
  fun somethingPausedSaysSo() {
    assertTrue(describeNowPlaying(listOf(podcast)).startsWith("Paused"))
  }

  /** "Something is playing" is useless to somebody asking what it is. */
  @Test
  fun aSessionWithNoMetadataStillNamesTheApp() {
    val said = describeNowPlaying(listOf(Session("Browser", playing = true, activeAt = 1)))
    assertTrue(said, said.contains("Browser"))
  }

  @Test
  fun nothingPlayingIsSaidPlainly() {
    assertEquals("Nothing is playing.", describeNowPlaying(emptyList()))
  }

  // ---- not claiming to have done the impossible ----

  /**
   * A transport control that goes nowhere is silent.
   *
   * The model says "skipped" and the same song keeps playing, which is the sort of small lie
   * that makes an assistant untrustworthy about everything else.
   */
  @Test
  fun whatASessionCannotDoIsNotAttempted() {
    assertTrue(canDo(music, MediaCommand.NEXT))
    assertFalse(canDo(music, MediaCommand.PREVIOUS))
    assertFalse(canDo(podcast, MediaCommand.PAUSE))
  }

  // ---- volume ----

  /**
   * Volume is the one media control that can actually hurt somebody.
   *
   * Headphones at full on a misheard "turn it up" is an injury rather than an annoyance, so a
   * run of steps stops short of maximum. Getting all the way up stays possible by hand, where
   * the person can hear what they are doing.
   */
  @Test
  fun turningUpRepeatedlyStopsShortOfMaximum() {
    var level = 0
    repeat(50) { level = volumeAfterStep(level, max = 15, up = true) }
    assertTrue("reached $level of 15", level < 15)
    assertEquals(12, level)
  }

  @Test
  fun aStepMovesAudiblyRatherThanImperceptibly() {
    assertTrue(volumeAfterStep(0, max = 15, up = true) > 0)
    assertTrue(volumeAfterStep(5, max = 100, up = true) > 5)
  }

  @Test
  fun turningDownGoesAllTheWayToSilence() {
    var level = 15
    repeat(50) { level = volumeAfterStep(level, max = 15, up = false) }
    assertEquals(0, level)
  }

  /** Pulling it down because it is above the ceiling would be a surprise of its own. */
  @Test
  fun somethingAlreadyLouderThanTheCeilingIsLeftAlone() {
    assertEquals(15, volumeAfterStep(15, max = 15, up = true))
  }

  @Test
  fun aDeviceReportingNoRangeIsLeftAlone() {
    assertEquals(3, volumeAfterStep(3, max = 0, up = true))
  }
}
