/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Making the engine's voice names fit to show a person.
 *
 * A speech engine names its voices for itself - `en-us-x-sfg#male_1-local` - and offers no readable
 * name anywhere. Whatever this produces is what someone picks a voice from, and the way it fails is
 * quiet: not a crash, just a list of rows that all say the same thing, which cannot be chosen
 * between deliberately.
 */
class VoiceCatalogTest {

  @Test
  fun readsTheCharacterOutOfAGoogleVoiceName() {
    assertEquals("Male 1", VoiceCatalog.describe("en-us-x-sfg#male_1-local"))
    assertEquals("Female 3", VoiceCatalog.describe("en-gb-x-gba#female_3-local"))
  }

  /** A network voice is named the same way; only where it runs differs. */
  @Test
  fun readsANetworkVoiceTheSameWay() {
    assertEquals("Male 2", VoiceCatalog.describe("en-us-x-iom#male_2-network"))
  }

  /** The locale's plain voice, which has no character in its name at all. */
  @Test
  fun namesThePlainVoiceSomethingSayable() {
    assertEquals("Standard", VoiceCatalog.describe("en-US-language"))
    assertEquals("Standard", VoiceCatalog.describe("en-us-language"))
  }

  /** Vendor engines use shapes Google's does not; anything is better than the raw name. */
  @Test
  fun copesWithAnUnfamiliarShape() {
    assertEquals("Tpd", VoiceCatalog.describe("en-us-x-tpd-network"))
    assertEquals("Sara", VoiceCatalog.describe("sara"))
  }

  /** Three rows all reading "Standard" is a picker that cannot be used on purpose. */
  @Test
  fun numbersVoicesThatWouldOtherwiseReadAlike() {
    val labels =
      VoiceCatalog.label(listOf("en-US-language", "en-GB-language", "en-us-x-sfg#male_1-local"))
    assertEquals(listOf("Standard 1", "Standard 2", "Male 1"), labels)
    assertNotEquals(labels[0], labels[1])
  }

  /** A name that is already distinct is left alone rather than gaining a pointless "1". */
  @Test
  fun leavesDistinctNamesUnnumbered() {
    assertEquals(
      listOf("Male 1", "Female 1"),
      VoiceCatalog.label(listOf("en-us-x-sfg#male_1-local", "en-us-x-sfg#female_1-local")),
    )
  }

  @Test
  fun labelsNothingWhenThereAreNoVoices() {
    assertEquals(listOf<String>(), VoiceCatalog.label(listOf()))
  }

  /**
   * A stored speed of zero means the setting has never been touched, because that is what an unset
   * proto float is - not a request for silence-speed playback.
   */
  @Test
  fun treatsAnUnsetSpeedAsTheDefault() {
    assertEquals(1.0f, VoiceCatalog.speedOrDefault(0f), 0.001f)
  }

  @Test
  fun keepsASpeedThatWasActuallyChosen() {
    assertEquals(1.4f, VoiceCatalog.speedOrDefault(1.4f), 0.001f)
    assertEquals(0.5f, VoiceCatalog.speedOrDefault(0.5f), 0.001f)
  }

  /** Out of range is unintelligible rather than merely fast, so it falls back rather than clamps. */
  @Test
  fun refusesASpeedOutsideWhatIsIntelligible() {
    assertEquals(1.0f, VoiceCatalog.speedOrDefault(9f), 0.001f)
    assertEquals(1.0f, VoiceCatalog.speedOrDefault(-1f), 0.001f)
  }
}
