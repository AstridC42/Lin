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

package com.google.ai.edge.gallery.voice

import com.google.ai.edge.gallery.voice.WakeWordService.Companion.containsWakePhrase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that turns a running transcript into "the user is talking to me".
 *
 * Both mistakes are costly in a way that is hard to notice from inside the app. Too eager and the
 * assistant opens itself during someone else's conversation, having recorded it. Too strict and
 * the feature simply appears not to work, with the microphone running the whole time it fails.
 */
class WakePhraseTest {

  @Test
  fun firesOnThePhrase() {
    assertTrue(containsWakePhrase("hey star", "hey star"))
    assertTrue(containsWakePhrase("Hey Star", "hey star"))
    assertTrue(containsWakePhrase("hey star, what is the time", "hey star"))
    assertTrue(containsWakePhrase("ok so, hey star", "hey star"))
  }

  /** Recognizers punctuate and capitalise inconsistently between utterances. */
  @Test
  fun ignoresPunctuationAndCasing() {
    assertTrue(containsWakePhrase("Hey, Star!", "hey star"))
    assertTrue(containsWakePhrase("...hey  star...", "hey star"))
  }

  /** A bare substring match would fire on every one of these. */
  @Test
  fun doesNotFireOnWordsThatMerelyContainThePhrase() {
    assertFalse(containsWakePhrase("hey started the car", "hey star"))
    assertFalse(containsWakePhrase("they starve", "hey star"))
    assertFalse(containsWakePhrase("heystar", "hey star"))
  }

  @Test
  fun doesNotFireOnThePhraseOutOfOrderOrIncomplete() {
    assertFalse(containsWakePhrase("star hey", "hey star"))
    assertFalse(containsWakePhrase("hey there star", "hey star"))
    assertFalse(containsWakePhrase("hey", "hey star"))
    assertFalse(containsWakePhrase("", "hey star"))
  }

  @Test
  fun supportsACustomPhrase() {
    assertTrue(containsWakePhrase("computer, what is the weather", "computer"))
    assertFalse(containsWakePhrase("my computerised system", "computer"))
    assertTrue(containsWakePhrase("okay assistant please help", "okay assistant"))
  }

  /** An empty phrase must never match, or the assistant opens on any sound at all. */
  @Test
  fun neverFiresOnAnEmptyPhrase() {
    assertFalse(containsWakePhrase("anything at all", ""))
    assertFalse(containsWakePhrase("anything at all", "   "))
  }
}
