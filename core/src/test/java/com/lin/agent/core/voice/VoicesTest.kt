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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing a voice, and saying which is which. */
class VoicesTest {

  private val british = VoiceOption("en-gb-x-gbb-local", "en", "GB", 400, needsNetwork = false)
  private val american = VoiceOption("en-us-x-tpf-local", "en", "US", 300, needsNetwork = false)
  private val online = VoiceOption("en-gb-x-rjs-network", "en", "GB", 500, needsNetwork = true)
  private val hebrew = VoiceOption("he-il-x-hec-local", "he", "IL", 300, needsNetwork = false)
  private val missing =
    VoiceOption("en-gb-x-gone-local", "en", "GB", 500, needsNetwork = false, installed = false)

  @Test
  fun `a voice that needs the network comes after every one that does not`() {
    // Even though it scores higher. A network voice sends every spoken reply to somebody's
    // server, which is the one thing this app does not do with anything else.
    val order = usableVoices(listOf(online, american, british), "en")
    assertEquals(listOf(british, american, online), order)
  }

  @Test
  fun `a voice that is not installed is not offered`() {
    assertTrue(usableVoices(listOf(missing), "en").isEmpty())
  }

  @Test
  fun `other languages drop out only when there is something to drop them for`() {
    assertEquals(listOf(british), usableVoices(listOf(british, hebrew), "en-GB"))
    // Nothing in the asked-for language: show what there is rather than an empty list.
    assertEquals(listOf(hebrew), usableVoices(listOf(hebrew), "en"))
  }

  @Test
  fun `a name a person can read`() {
    assertEquals("English (United Kingdom) · gbb · on this phone", voiceLabel(british))
    assertEquals("English (United Kingdom) · rjs · online", voiceLabel(online))
  }

  @Test
  fun `a name with no variant in it still reads`() {
    val plain = VoiceOption("en-US-language", "en", "US", 300, needsNetwork = false)
    assertEquals("English (United States) · on this phone", voiceLabel(plain))
  }

  @Test
  fun `the chosen voice is used while it is there`() {
    assertEquals(american, chosenVoice(listOf(british, american), "en-us-x-tpf-local"))
  }

  @Test
  fun `a voice that has gone falls back rather than failing to speak`() {
    // Engines get switched and language packs removed. Silence would be the worse answer.
    assertEquals(british, chosenVoice(listOf(british, american), "en-gb-x-vanished-local"))
    assertNull(chosenVoice(emptyList(), "anything"))
  }
}
