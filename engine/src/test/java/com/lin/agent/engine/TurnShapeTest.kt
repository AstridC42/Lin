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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which requests need a conversation of their own, on a backend where that is the only way to
 * honour them.
 *
 * Written after finding that the on-device backend read the tool set off a request and nothing
 * else, so a system prompt and a response schema were taken and dropped. Both failures were
 * invisible from outside: an assistant with no instructions still answers, and an unconstrained
 * answer to a schema request just parses to nothing - which reads as a model with nothing to
 * say. The whole point of these tests is that the next thing added to a request cannot be
 * forgotten here in the same silence.
 */
class TurnShapeTest {

  private fun ask(
    systemPrompt: String = "",
    tools: List<ToolSpec> = emptyList(),
    responseSchema: String? = null,
  ) =
    TurnRequest(
      messages = listOf(Message(Message.Role.USER, "hello")),
      systemPrompt = systemPrompt,
      tools = tools,
      responseSchema = responseSchema,
    )

  private val aTool = ToolSpec("call", "Calls somebody", "{}")

  @Test
  fun aPlainQuestionNeedsNothingBuiltForIt() {
    assertNull(shapeOf(ask()))
  }

  /** Whitespace is not an instruction. */
  @Test
  fun aBlankSystemPromptIsNoSystemPrompt() {
    assertNull(shapeOf(ask(systemPrompt = "   \n ")))
  }

  @Test
  fun aSystemPromptAloneIsEnoughToNeedOne() {
    val shape = shapeOf(ask(systemPrompt = "You are Lin."))
    assertEquals("You are Lin.", shape?.systemPrompt)
  }

  @Test
  fun aSchemaAloneIsEnoughToNeedOne() {
    val shape = shapeOf(ask(responseSchema = """{"type":"object"}"""))
    assertTrue(shape?.structured == true)
  }

  @Test
  fun toolsAloneAreEnoughToNeedOne() {
    assertEquals(listOf(aTool), shapeOf(ask(tools = listOf(aTool)))?.tools)
  }

  // ---- what may share a conversation, and what may not ----

  @Test
  fun twoIdenticalRequestsShareAShape() {
    assertEquals(
      shapeOf(ask(systemPrompt = "You are Lin.", tools = listOf(aTool))),
      shapeOf(ask(systemPrompt = "You are Lin.", tools = listOf(aTool))),
    )
  }

  /** The prompt is baked into the conversation, so a different prompt cannot reuse it. */
  @Test
  fun adifferentSystemPromptIsADifferentShape() {
    assertNotEquals(shapeOf(ask(systemPrompt = "You are Lin.")), shapeOf(ask(systemPrompt = "Be terse.")))
  }

  @Test
  fun aDifferentToolSetIsADifferentShape() {
    assertNotEquals(shapeOf(ask(tools = listOf(aTool))), shapeOf(ask(tools = emptyList(), systemPrompt = "x")))
  }

  /**
   * A structured turn is never the same shape as the chat it followed.
   *
   * The digest sends a whole transcript. Sharing the chat's conversation would put that
   * transcript into the chat's history, which is both wrong and not something somebody would
   * expect from a feature that files away notes.
   */
  @Test
  fun aStructuredTurnIsNeverTheSameShapeAsAnUnstructuredOne() {
    assertNotEquals(
      shapeOf(ask(systemPrompt = "Find the facts.", responseSchema = """{"type":"object"}""")),
      shapeOf(ask(systemPrompt = "Find the facts.")),
    )
  }
}
