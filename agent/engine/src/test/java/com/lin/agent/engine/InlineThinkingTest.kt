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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineThinkingTest {

  private fun splitAll(vararg chunks: String): Split {
    val splitter = InlineThinking()
    val thought = StringBuilder()
    val text = StringBuilder()
    for (chunk in chunks) splitter.feed(chunk).let { thought.append(it.thought); text.append(it.text) }
    splitter.finish().let { thought.append(it.thought); text.append(it.text) }
    return Split(thought.toString(), text.toString())
  }

  @Test
  fun `inline reasoning goes to thinking and the answer stays the answer`() {
    assertEquals(
      Split("The user greets me. Greet back.", "Hello, Looper."),
      splitAll("<think>The user greets me. Greet back.</think>\n\nHello, Looper."),
    )
  }

  @Test
  fun `a tag split across chunks is still a tag`() {
    // The streaming case: "<thi" arrives, then "nk>". Shown as it came, the first half would
    // appear on screen before anything knew it was a tag.
    assertEquals(Split("plan", "Answer"), splitAll("<thi", "nk>pl", "an</th", "ink>", "Answer"))
  }

  @Test
  fun `nothing that might be a tag is shown before it is known not to be one`() {
    val splitter = InlineThinking()
    assertEquals(Split("", "Hi "), splitter.feed("Hi <th"))
    // It was not a tag after all, and the held-back text comes out intact.
    assertEquals(Split("", "<the end>"), splitter.feed("e end>"))
  }

  @Test
  fun `a reply with no thinking passes through untouched`() {
    assertEquals(Split("", "Just an answer, 2 < 3."), splitAll("Just an answer, ", "2 < 3."))
  }

  @Test
  fun `a reply cut off while thinking keeps what it thought`() {
    assertEquals(Split("half a thou", ""), splitAll("<think>half a thou"))
  }

  @Test
  fun `the refusal ollama sends is recognised and nothing else is`() {
    // The exact body from the screenshot.
    val said = """{"error":"\"hf.co/unsloth/gemma-4-12B-it-qat-GGUF:UD-Q4_K_XL\" does not support thinking"}"""
    assertTrue(thinkingRefused(400, said))
    assertFalse(thinkingRefused(400, """{"error":"model not found"}"""))
    assertFalse(thinkingRefused(500, said))
  }

  @Test
  fun `a model that cannot be asked is not asked`() {
    val request = TurnRequest(messages = listOf(Message(Message.Role.USER, "hi")))
    assertTrue(JSONObject(chatRequestBody("m", request)).has("think"))
    assertFalse(JSONObject(chatRequestBody("m", request, sendThink = false)).has("think"))
  }

  // ---- the whole turn ----

  private fun stream(status: Int, lines: List<String>, error: String = "") =
    object : ServerBackend.Stream {
      override val status = status
      override val lines = lines.asSequence()
      override val errorBody = error

      override fun close() = Unit
    }

  private val refusal = """{"error":"\"gemma\" does not support thinking"}"""

  private val inlineReply =
    listOf(
      """{"message":{"role":"assistant","content":"<think>Greet them."},"done":false}""",
      """{"message":{"role":"assistant","content":"</think>Hello."},"done":false}""",
      """{"message":{"role":"assistant","content":""},"done":true}""",
    )

  @Test
  fun `refused thinking is asked again without it, and the turn succeeds`() = runTest {
    val bodies = mutableListOf<String>()
    val backend =
      ServerBackend(
        "host-a",
        "",
        "gemma-retry",
        openStream = { _, _, body ->
          bodies.add(body)
          if (JSONObject(body).has("think")) stream(400, emptyList(), refusal) else stream(200, inlineReply)
        },
      )
    val events = backend.converse(TurnRequest(messages = listOf(Message(Message.Role.USER, "hi")))).toList()

    assertEquals(2, bodies.size)
    assertTrue(JSONObject(bodies[0]).has("think"))
    assertFalse(JSONObject(bodies[1]).has("think"))
    assertTrue(events.toString(), events.contains(TurnEvent.Thought("Greet them.")))
    assertEquals(TurnEvent.Done("Hello."), events.last())
    assertFalse(events.toString(), events.any { it is TurnEvent.Failed })
  }

  @Test
  fun `once refused, the next turn does not ask at all`() = runTest {
    val bodies = mutableListOf<String>()
    val backend =
      ServerBackend(
        "host-b",
        "",
        "gemma-remembered",
        openStream = { _, _, body ->
          bodies.add(body)
          if (JSONObject(body).has("think")) stream(400, emptyList(), refusal) else stream(200, inlineReply)
        },
      )
    val ask = TurnRequest(messages = listOf(Message(Message.Role.USER, "hi")))
    backend.converse(ask).toList()
    backend.converse(ask).toList()

    // Two for the first turn, one for the second - no refusal paid twice.
    assertEquals(3, bodies.size)
    assertFalse(JSONObject(bodies[2]).has("think"))
  }

  @Test
  fun `any other 400 is still reported, not retried`() = runTest {
    var opened = 0
    val backend =
      ServerBackend("host-c", "", "gemma-other", openStream = { _, _, _ -> opened++; stream(400, emptyList(), """{"error":"bad"}""") })
    val events = backend.converse(TurnRequest(messages = listOf(Message(Message.Role.USER, "hi")))).toList()
    assertEquals(1, opened)
    assertTrue(events.last() is TurnEvent.Failed)
  }
}
