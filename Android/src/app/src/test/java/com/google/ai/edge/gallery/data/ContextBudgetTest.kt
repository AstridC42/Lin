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

package com.google.ai.edge.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much context this app asks a model for.
 *
 * Pinned because getting it wrong does not look like a configuration problem. The allowlist's
 * default is 4,000 tokens - upstream's number for a chat app with no tools - and this assistant's
 * system prompt plus the schemas of its forty-odd tools come to roughly 4,200 before the user has
 * typed a word. The result was "Input token ids are too long: 4230 >= 4000" on the first message
 * of a brand new conversation, the session thrown away and rebuilt, and three separate bug
 * reports that all looked like something else: web search not working, no reply arriving, a loop
 * after a search.
 *
 * A model that advertises a real context length should have it used.
 */
class ContextBudgetTest {

  /** Roughly what the prompt and the tool schemas cost before anything is said. */
  private val fixedOverhead = 4_300

  /** The case from the report: Gemma-4-E4B, 32k of context, asked for 4k. */
  @Test
  fun takesMoreContextWhenTheModelHasIt() {
    val chosen = contextForAssistant(allowlistDefault = 4_000, maxContextLength = 32_000)
    assertTrue(
      "a first message must fit, and $chosen leaves ${chosen - fixedOverhead} for it",
      chosen > fixedOverhead,
    )
    assertEquals(ASSISTANT_CONTEXT_TOKENS, chosen)
  }

  /** Never more than the model can actually hold, whatever this app would like. */
  @Test
  fun neverAsksForMoreThanTheModelHas() {
    assertEquals(6_000, contextForAssistant(allowlistDefault = 4_000, maxContextLength = 6_000))
    assertEquals(4_096, contextForAssistant(allowlistDefault = 4_096, maxContextLength = 4_096))
  }

  /** A model that already asks for more keeps it: this only ever raises. */
  @Test
  fun neverLowersWhatTheAllowlistAsksFor() {
    assertEquals(16_000, contextForAssistant(allowlistDefault = 16_000, maxContextLength = 32_000))
  }

  /** Most entries say nothing about context, and must be left exactly as they are. */
  @Test
  fun leavesAModelThatSaysNothingAlone() {
    assertEquals(4_096, contextForAssistant(allowlistDefault = 4_096, maxContextLength = null))
    assertEquals(4_096, contextForAssistant(allowlistDefault = 4_096, maxContextLength = 0))
  }

  /**
   * The headroom this leaves is the whole point, so it is stated rather than implied. If the
   * overhead grows past this, the arithmetic here fails before a phone does.
   */
  @Test
  fun leavesRoomForAConversationNotJustAPrompt() {
    val room = ASSISTANT_CONTEXT_TOKENS - fixedOverhead
    assertTrue("only $room tokens left for the conversation", room >= 3_000)
  }
}
