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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn that asked for a tool, written back into the conversation.
 *
 * Reported from a phone: `search_web` ran, its card said Done, and the turn then died with
 * "this is taking more steps than it should - stopped after 6 tool calls". The model could
 * not see its own result, because the conversation sent back to the server was a user
 * question followed by a tool result with nothing in between that had asked for it.
 */
class ToolCallHistoryTest {

  @Test
  fun `the turn that asked for the tool is in the next request`() = runTest {
    val backend = Scripted(ToolInvocation("search_web", mapOf("query" to "lego")))
    TurnLoop(backend, ToolRegistry(listOf(Found("search_web", "Three results."))), AgentRole.ORCHESTRATOR)
      .run(listOf(Message(Message.Role.USER, "search for lego")))
      .toList()

    val second = backend.seen[1].messages
    val asking = second.firstOrNull { it.role == Message.Role.ASSISTANT }
    assertNotNull("the assistant turn that asked is missing", asking)
    assertEquals("search_web", asking!!.toolCalls.single().name)
  }

  @Test
  fun `the asking turn comes before the result it explains`() = runTest {
    val backend = Scripted(ToolInvocation("search_web", mapOf("query" to "lego")))
    TurnLoop(backend, ToolRegistry(listOf(Found("search_web", "Three results."))), AgentRole.ORCHESTRATOR)
      .run(listOf(Message(Message.Role.USER, "search for lego")))
      .toList()

    val second = backend.seen[1].messages
    val asked = second.indexOfFirst { it.toolCalls.isNotEmpty() }
    val answered = second.indexOfFirst { it.role == Message.Role.TOOL }
    assertTrue("asked at $asked, answered at $answered", asked in 0 until answered)
  }

  @Test
  fun `the result names the call it answers`() = runTest {
    val backend = Scripted(ToolInvocation("search_web", mapOf("query" to "lego")))
    TurnLoop(backend, ToolRegistry(listOf(Found("search_web", "Three results."))), AgentRole.ORCHESTRATOR)
      .run(listOf(Message(Message.Role.USER, "search for lego")))
      .toList()

    val second = backend.seen[1].messages
    val id = second.first { it.toolCalls.isNotEmpty() }.toolCalls.single().id
    assertTrue("no id was minted", id.isNotEmpty())
    assertEquals(id, second.first { it.role == Message.Role.TOOL }.toolCallId)
  }

  @Test
  fun `two calls in one turn get two different ids`() = runTest {
    val backend =
      Scripted(ToolInvocation("search_web", mapOf("q" to "a")), ToolInvocation("weather", emptyMap()))
    TurnLoop(
        backend,
        ToolRegistry(listOf(Found("search_web", "results"), Found("weather", "sunny"))),
        AgentRole.ORCHESTRATOR,
      )
      .run(listOf(Message(Message.Role.USER, "both please")))
      .toList()

    val second = backend.seen[1].messages
    val ids = second.first { it.toolCalls.isNotEmpty() }.toolCalls.map { it.id }
    assertEquals(2, ids.distinct().size)
    assertEquals(ids, second.filter { it.role == Message.Role.TOOL }.map { it.toolCallId })
  }

  @Test
  fun `what the model said on the way to asking rides on the same turn`() = runTest {
    val backend = Scripted(ToolInvocation("search_web", mapOf("q" to "a")), spoken = "Let me look.")
    TurnLoop(backend, ToolRegistry(listOf(Found("search_web", "results"))), AgentRole.ORCHESTRATOR)
      .run(listOf(Message(Message.Role.USER, "search")))
      .toList()

    val asking = backend.seen[1].messages.first { it.toolCalls.isNotEmpty() }
    assertEquals("Let me look.", asking.text)
  }

  // ---- what each dialect puts on the wire ----

  private val asked =
    listOf(
      Message(Message.Role.USER, "search for lego"),
      Message(
        Message.Role.ASSISTANT,
        "",
        toolCalls = listOf(ToolInvocation("search_web", mapOf("query" to "lego"), id = "call_1")),
      ),
      Message(Message.Role.TOOL, "Three results.", toolName = "search_web", toolCallId = "call_1"),
    )

  @Test
  fun `ollama is sent the call with its arguments as an object`() {
    val body = JSONObject(chatRequestBody("qwen3", TurnRequest(messages = asked)))
    val assistant = body.getJSONArray("messages").getJSONObject(1)
    val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
    assertEquals("search_web", call.getJSONObject("function").getString("name"))
    assertEquals("lego", call.getJSONObject("function").getJSONObject("arguments").getString("query"))
  }

  @Test
  fun `openai is sent the call with its arguments as a string`() {
    // The one place the dialects differ in shape rather than spelling. A server handed an
    // object where it expects serialised JSON sees a call with no arguments, or refuses it.
    val body = JSONObject(openAiRequestBody("gpt", TurnRequest(messages = asked)))
    val assistant = body.getJSONArray("messages").getJSONObject(1)
    val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
    assertEquals("function", call.getString("type"))
    assertEquals("call_1", call.getString("id"))
    val arguments = call.getJSONObject("function").get("arguments")
    assertTrue("arguments should be a string, was ${arguments::class.simpleName}", arguments is String)
    assertEquals("lego", JSONObject(arguments as String).getString("query"))
  }

  @Test
  fun `openai is told which call each result answers`() {
    val body = JSONObject(openAiRequestBody("gpt", TurnRequest(messages = asked)))
    val result = body.getJSONArray("messages").getJSONObject(2)
    assertEquals("tool", result.getString("role"))
    assertEquals("call_1", result.getString("tool_call_id"))
  }

  @Test
  fun `an ordinary message carries no tool fields at all`() {
    val plain = listOf(Message(Message.Role.USER, "hello"))
    for (body in listOf(chatRequestBody("m", TurnRequest(messages = plain)),
                        openAiRequestBody("m", TurnRequest(messages = plain)))) {
      val message = JSONObject(body).getJSONArray("messages").getJSONObject(0)
      assertFalse(body, message.has("tool_calls"))
      assertFalse(body, message.has("tool_call_id"))
    }
  }

  // ---- helpers ----

  private class Found(override val name: String, private val answer: String) : AgentTool {
    override val description = "a tool for tests"
    override val parameters = emptyList<ToolParameter>()
    override val roles = AgentRole.entries.toSet()

    override suspend fun run(arguments: Map<String, Any?>): ToolResult = ToolResult.Ok(answer)
  }

  /** Asks for the calls once, then answers. */
  private class Scripted(private vararg val calls: ToolInvocation, private val spoken: String = "") :
    LlmBackend {
    val seen = mutableListOf<TurnRequest>()

    override val id = "scripted"
    override val capabilities = BackendCapabilities(true, false, false, 8192)

    override suspend fun reachable() = true

    override fun converse(request: TurnRequest): Flow<TurnEvent> = flow {
      val exchange = seen.size
      seen.add(request)
      if (exchange == 0) {
        if (spoken.isNotEmpty()) emit(TurnEvent.Token(spoken))
        emit(TurnEvent.ToolCalls(calls.toList()))
      } else {
        emit(TurnEvent.Done("Here is what I found."))
      }
    }
  }
}
