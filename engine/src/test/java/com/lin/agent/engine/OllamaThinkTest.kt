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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaThinkTest {

  private fun body(reasoning: Reasoning, schema: String? = null) =
    JSONObject(
      chatRequestBody(
        "qwen3:32b",
        TurnRequest(
          messages = listOf(Message(Message.Role.USER, "hi")),
          reasoning = reasoning,
          responseSchema = schema,
        ),
      )
    )

  @Test
  fun `the three levels are three different requests`() {
    // The bug: Lin sent `think: true` whatever was chosen, so picking the cheaper level
    // changed nothing on a server and somebody who asked for a little thinking got as much
    // as the model felt like.
    val off = body(Reasoning.OFF).get("think")
    val brief = body(Reasoning.BRIEF).get("think")
    val thorough = body(Reasoning.THOROUGH).get("think")
    assertNotEquals(off, brief)
    assertNotEquals(brief, thorough)
    assertNotEquals(off, thorough)
  }

  @Test
  fun `the levels are the words ollama accepts`() {
    assertEquals("low", body(Reasoning.BRIEF).get("think"))
    assertEquals("high", body(Reasoning.THOROUGH).get("think"))
  }

  @Test
  fun `off is sent rather than left out`() {
    // Omitting the field means "off" only for a model that was not going to think anyway. A
    // model that reasons by default reasons when nothing is said.
    val body = body(Reasoning.OFF)
    assertTrue("think is missing entirely", body.has("think"))
    assertEquals(false, body.get("think"))
  }

  @Test
  fun `a structured turn never thinks, whatever was chosen`() {
    // The decoder is constrained to the schema for those turns and thinking is text that is
    // not in it.
    for (level in Reasoning.entries) {
      assertEquals("$level", false, body(level, schema = """{"type":"object"}""").get("think"))
    }
  }

  @Test
  fun `the level is chosen from the setting alone`() {
    assertEquals(OllamaThink.OFF, ollamaThink(Reasoning.OFF, structured = false))
    assertEquals(OllamaThink.LOW, ollamaThink(Reasoning.BRIEF, structured = false))
    assertEquals(OllamaThink.HIGH, ollamaThink(Reasoning.THOROUGH, structured = false))
  }

  @Test
  fun `what goes on the wire is a boolean or one of ollama's words`() {
    val allowed = setOf(false, "low", "medium", "high", "max")
    for (level in OllamaThink.entries) {
      assertTrue("${level.wire}", level.wire in allowed)
    }
  }

  @Test
  fun `turning thinking off does not turn tool calling off with it`() {
    // They are set in the same body and a mistake in one is easy to make in the other.
    val spec = ToolSpec("clock", "the time", """{"type":"object","properties":{}}""")
    val body =
      JSONObject(
        chatRequestBody(
          "m",
          TurnRequest(
            messages = listOf(Message(Message.Role.USER, "hi")),
            reasoning = Reasoning.OFF,
            tools = listOf(spec),
          ),
        )
      )
    assertEquals(false, body.get("think"))
    assertFalse("tools went missing", body.optJSONArray("tools") == null)
  }
}
