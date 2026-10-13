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

package com.lin.agent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** When the model is allowed to think, and when it must not be. */
class ReasoningTest {

  @Test
  fun offMeansNoBudgetAtAll() {
    assertNull(thinkingFor(Reasoning.OFF, structured = false))
  }

  @Test
  fun theLevelsDifferAndTheDeeperOneIsBigger() {
    val brief = thinkingFor(Reasoning.BRIEF, structured = false)
    val thorough = thinkingFor(Reasoning.THOROUGH, structured = false)
    assertNotNull(brief)
    assertNotNull(thorough)
    assertTrue(thorough!!.tokens > brief!!.tokens)
  }

  /**
   * The rule worth having a test for.
   *
   * A structured turn's decoder is constrained to a schema that has no room for a chain of
   * thought, so asking for both is asking the model to emit text through a grammar that forbids
   * it. The digest is the one structured caller, and it is reading a transcript for facts rather
   * than solving anything - there is nothing for thinking to buy there.
   */
  @Test
  fun aTurnBoundToASchemaNeverThinks() {
    for (level in Reasoning.entries) {
      assertNull("$level still thought on a structured turn", thinkingFor(level, structured = true))
    }
  }

  // ---- reading the stored setting ----

  @Test
  fun aStoredNameRoundTrips() {
    for (level in Reasoning.entries) {
      assertEquals(level, reasoningNamed(level.name))
    }
  }

  @Test
  fun anythingUnrecognisedReadsAsOffRatherThanThrowing() {
    assertEquals(Reasoning.OFF, reasoningNamed(""))
    assertEquals(Reasoning.OFF, reasoningNamed("   "))
    assertEquals(Reasoning.OFF, reasoningNamed("EXHAUSTIVE"))
  }

  /** Written by one build, read by another: case and padding should not matter. */
  @Test
  fun theNameIsReadLeniently() {
    assertEquals(Reasoning.THOROUGH, reasoningNamed(" thorough "))
  }

  // ---- what reaches the server ----

  @Test
  fun aThinkingTurnAsksTheServerToThink() {
    val body =
      JSONObject(
        chatRequestBody(
          "gemma4",
          TurnRequest(
            messages = listOf(Message(Message.Role.USER, "why")),
            reasoning = Reasoning.THOROUGH,
          ),
        )
      )
    assertTrue(body.optBoolean("think"))
  }

  /**
   * An older server rejects a request naming a field it does not know, so a turn that wants no
   * thinking must not mention it at all rather than send `think: false`.
   */
  @Test
  fun aTurnThatWantsNoThinkingDoesNotMentionItToTheServer() {
    val body =
      JSONObject(
        chatRequestBody(
          "gemma4",
          TurnRequest(messages = listOf(Message(Message.Role.USER, "hi"))),
        )
      )
    assertFalse(body.has("think"))
  }

  @Test
  fun aSchemaTurnDoesNotAskTheServerToThinkEither() {
    val body =
      JSONObject(
        chatRequestBody(
          "gemma4",
          TurnRequest(
            messages = listOf(Message(Message.Role.USER, "extract")),
            responseSchema = """{"type":"object"}""",
            reasoning = Reasoning.THOROUGH,
          ),
        )
      )
    assertFalse(body.has("think"))
  }

  // ---- what comes back ----

  @Test
  fun thinkingIsReadOffItsOwnFieldAndNotTheAnswer() {
    val chunk =
      readChunk("""{"message":{"content":"42.","thinking":"Let me count them."},"done":false}""")
    assertEquals("Let me count them.", chunk?.thinking)
    assertEquals("42.", chunk?.text)
  }

  @Test
  fun aReplyWithNoThinkingHasNone() {
    assertEquals("", readChunk("""{"message":{"content":"42."},"done":true}""")?.thinking)
  }
}
