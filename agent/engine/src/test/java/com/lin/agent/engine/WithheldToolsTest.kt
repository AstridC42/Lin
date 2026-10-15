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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tool that exists but is not offered this turn, and a failure that follows a success.
 *
 * Both written from the same screenshots. "Tell me a short story" spent an exchange delegating
 * to a server that was not configured before writing the story itself; "what have I got on
 * today?" read the calendar successfully, failed on the weather, and answered about the
 * weather.
 */
class WithheldToolsTest {

  @Test
  fun `a withheld tool is not in the list the model is given`() = runTest {
    val backend = Scripted(listOf(listOf(TurnEvent.Done("Here is a story."))))
    TurnLoop(
        backend,
        ToolRegistry(listOf(ok("delegate", "never"), ok("clock", "10:30"))),
        AgentRole.ORCHESTRATOR,
        withhold = setOf("delegate"),
      )
      .run(asked("tell me a short story"))
      .toList()

    val offered = backend.seen.first().tools.map { it.name }
    assertEquals(listOf("clock"), offered)
  }

  @Test
  fun `withholding nothing offers everything`() = runTest {
    val backend = Scripted(listOf(listOf(TurnEvent.Done("ok"))))
    TurnLoop(backend, ToolRegistry(listOf(ok("delegate", "x"), ok("clock", "y"))), AgentRole.ORCHESTRATOR)
      .run(asked("hi"))
      .toList()

    assertEquals(listOf("clock", "delegate"), backend.seen.first().tools.map { it.name }.sorted())
  }

  @Test
  fun `a withheld tool cannot be called even if the model asks for it anyway`() = runTest {
    // A conversation that has been going a while has the tool's name in it from an earlier
    // turn, and a model that has seen it once will ask for it again. Withholding it from the
    // list alone would let that through.
    val delegate = Recording("delegate", "handed over")
    val backend =
      Scripted(
        listOf(
          listOf(TurnEvent.ToolCalls(listOf(ToolInvocation("delegate", emptyMap())))),
          listOf(TurnEvent.Done("I wrote it myself.")),
        )
      )

    val events =
      TurnLoop(backend, ToolRegistry(listOf(delegate)), AgentRole.ORCHESTRATOR, withhold = setOf("delegate"))
        .run(asked("tell me a story"))
        .toList()

    assertEquals(0, delegate.runs)
    assertTrue(events.any { it is LoopEvent.ToolFinished && !it.ok })
    assertEquals(LoopEvent.Done("I wrote it myself."), events.last())
  }

  @Test
  fun `a failure on its own is reported plainly`() {
    val said = toolFailureText("the weather service could not be reached", somethingElseWorked = false)
    assertTrue(said, said.startsWith("That did not work:"))
    assertFalse(said, said.contains("Another tool"))
  }

  @Test
  fun `a failure after a success says the answer is already there`() {
    val said = toolFailureText("I do not know your location", somethingElseWorked = true)
    assertTrue(said, said.contains("I do not know your location"))
    // The sentence that stops "what have I got on today?" being answered with a complaint
    // about the weather while the calendar result sits unused two messages above.
    assertTrue(said, said.contains("Another tool"))
  }

  @Test
  fun `the model is told a tool worked when one did before the failure`() = runTest {
    val backend =
      Scripted(
        listOf(
          listOf(TurnEvent.ToolCalls(listOf(ToolInvocation("calendar", emptyMap())))),
          listOf(TurnEvent.ToolCalls(listOf(ToolInvocation("weather", emptyMap())))),
          listOf(TurnEvent.Done("You have a dentist at 4:30.")),
        )
      )
    val tools = listOf(ok("calendar", "Dentist at 4:30."), fails("weather", "I do not know your location"))

    TurnLoop(backend, ToolRegistry(tools), AgentRole.ORCHESTRATOR).run(asked("what have I got on today?")).toList()

    val afterTheFailure = backend.seen[2].messages.last { it.role == Message.Role.TOOL }
    assertTrue(afterTheFailure.text, afterTheFailure.text.contains("Another tool"))
  }

  @Test
  fun `a failure with nothing before it is not told that something worked`() = runTest {
    val backend =
      Scripted(
        listOf(
          listOf(TurnEvent.ToolCalls(listOf(ToolInvocation("weather", emptyMap())))),
          listOf(TurnEvent.Done("I could not get the weather.")),
        )
      )

    TurnLoop(backend, ToolRegistry(listOf(fails("weather", "no location"))), AgentRole.ORCHESTRATOR)
      .run(asked("what's the weather"))
      .toList()

    val afterTheFailure = backend.seen[1].messages.last { it.role == Message.Role.TOOL }
    assertFalse(afterTheFailure.text, afterTheFailure.text.contains("Another tool"))
  }

  // ---- helpers ----

  private fun asked(text: String) = listOf(Message(Message.Role.USER, text))

  private fun ok(name: String, answer: String) = Recording(name, answer)

  private fun fails(name: String, why: String) = Failing(name, why)

  private class Recording(override val name: String, private val answer: String) : AgentTool {
    override val description = "a tool for tests"
    override val parameters = emptyList<ToolParameter>()
    override val roles = AgentRole.entries.toSet()
    var runs = 0
      private set

    override suspend fun run(arguments: Map<String, Any?>): ToolResult {
      runs++
      return ToolResult.Ok(answer)
    }
  }

  private class Failing(override val name: String, private val why: String) : AgentTool {
    override val description = "a tool for tests"
    override val parameters = emptyList<ToolParameter>()
    override val roles = AgentRole.entries.toSet()

    override suspend fun run(arguments: Map<String, Any?>): ToolResult = ToolResult.Failed(why)
  }

  private class Scripted(private val script: List<List<TurnEvent>>) : LlmBackend {
    val seen = mutableListOf<TurnRequest>()

    override val id = "scripted"
    override val capabilities = BackendCapabilities(true, false, false, 8192)

    override suspend fun reachable() = true

    override fun converse(request: TurnRequest): Flow<TurnEvent> = flow {
      val index = seen.size
      seen.add(request)
      val events =
        script.getOrNull(index)
          ?: error("The loop asked for exchange ${index + 1}; the script has ${script.size}.")
      events.forEach { emit(it) }
    }
  }
}
