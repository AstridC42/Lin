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
 * The agent loop, without a model.
 *
 * This is the test seam the first version never had, and the reason the loop is written by hand
 * rather than left to the engine's automatic tool calling: every branch here is one that would
 * otherwise only be reachable by holding a phone.
 */
class TurnLoopTest {

  // ---- the property everything else depends on ----

  /**
   * A backend that never asks for a tool still holds a conversation.
   *
   * First, because it is the one that must not break. Tool calling cannot be verified off a
   * device, so the loop is built to be additive: if no call ever arrives this is a passthrough,
   * and a bug in the tool path cannot cost plain chat.
   */
  @Test
  fun aBackendThatNeverCallsAToolJustAnswers() = runTest {
    val events = loop(ScriptedBackend(listOf(says("Hello there.")))).run(ask("hi")).toList()
    assertEquals(listOf(LoopEvent.Done("Hello there.")), events)
  }

  @Test
  fun aToolResultComesBackAndTheModelAnswersWithIt() = runTest {
    val clock = recordingTool("clock", "It is 10:30.")
    val backend =
      ScriptedBackend(listOf(calls(ToolInvocation("clock", emptyMap())), says("It is half past.")))

    val events = loop(backend, listOf(clock)).run(ask("what time is it")).toList()

    assertEquals(
      listOf(
        LoopEvent.ToolStarted("clock"),
        // The tool's own words, carried up as it said them: the screen shows them on
        // request, and what the model made of them is not a substitute.
        LoopEvent.ToolFinished("clock", ok = true, said = "It is 10:30."),
        LoopEvent.Done("It is half past."),
      ),
      events,
    )
    assertEquals(1, clock.runs)
  }

  /** The tool's answer has to reach the next exchange, or the loop is theatre. */
  @Test
  fun theToolResultIsPutInFrontOfTheModel() = runTest {
    val backend =
      ScriptedBackend(listOf(calls(ToolInvocation("clock", emptyMap())), says("done")))
    loop(backend, listOf(recordingTool("clock", "It is 10:30."))).run(ask("time?")).toList()

    val second = backend.seen[1].messages
    val toolTurn = second.last()
    assertEquals(Message.Role.TOOL, toolTurn.role)
    assertEquals("clock", toolTurn.toolName)
    assertEquals("It is 10:30.", toolTurn.text)
  }

  /** Anything said on the way to a tool call is part of the reply, not thrown away. */
  @Test
  fun wordsSpokenBeforeAToolCallAreKept() = runTest {
    val backend =
      ScriptedBackend(
        listOf(
          saysAndCalls("Let me check.", ToolInvocation("clock", emptyMap())),
          says("Half past ten."),
        )
      )
    loop(backend, listOf(recordingTool("clock", "10:30"))).run(ask("time?")).toList()

    val second = backend.seen[1].messages
    assertTrue(
      second.any { it.role == Message.Role.ASSISTANT && it.text == "Let me check." },
    )
  }

  // ---- role scoping ----

  /**
   * A sub-agent naming an orchestrator's tool gets told no, by the lookup itself.
   *
   * The structural fix: there is no version of the lookup that returns a tool the caller may not
   * run, so this cannot be forgotten in a new call site.
   */
  @Test
  fun aToolOutsideTheRoleCannotBeRunEvenIfTheModelNamesIt() = runTest {
    val delegate = recordingTool("delegate", "sent", roles = setOf(AgentRole.ORCHESTRATOR))
    val backend =
      ScriptedBackend(listOf(calls(ToolInvocation("delegate", emptyMap())), says("never mind")))

    val events =
      TurnLoop(backend, ToolRegistry(listOf(delegate)), AgentRole.SUB_AGENT)
        .run(ask("delegate this"))
        .toList()

    assertEquals(0, delegate.runs)
    val finished = events.filterIsInstance<LoopEvent.ToolFinished>().single()
    assertEquals("delegate", finished.name)
    assertFalse(finished.ok)
    // The same sentence the model was given, so the screen can show why nothing happened.
    assertTrue(finished.said, finished.said.contains("no tool called"))
    val told = backend.seen[1].messages.last()
    assertTrue(told.text, told.text.contains("no tool called"))
  }

  @Test
  fun onlyTheRolesToolsAreOffered() = runTest {
    val registry =
      ToolRegistry(
        listOf(
          recordingTool("delegate", "x", roles = setOf(AgentRole.ORCHESTRATOR)),
          recordingTool("search", "x", roles = setOf(AgentRole.SUB_AGENT)),
          recordingTool("answer", "x", roles = AgentRole.entries.toSet()),
        )
      )
    val backend = ScriptedBackend(listOf(says("hi")))
    TurnLoop(backend, registry, AgentRole.SUB_AGENT).run(ask("hi")).toList()

    assertEquals(listOf("answer", "search"), backend.seen[0].tools.map { it.name })
  }

  // ---- not running away ----

  @Test
  fun theBudgetStopsARunawayLoop() = runTest {
    // Always asks for one more tool and never answers.
    val backend = ScriptedBackend(List(20) { calls(ToolInvocation("tick", mapOf("n" to it))) })
    val events =
      TurnLoop(
          backend,
          ToolRegistry(listOf(recordingTool("tick", "ok"))),
          AgentRole.ORCHESTRATOR,
          budget = Budget(maxToolCalls = 3),
        )
        .run(ask("go"))
        .toList()

    assertEquals(3, events.count { it is LoopEvent.ToolStarted })
    val failure = events.last() as LoopEvent.Failed
    assertTrue(failure.detail, failure.detail.contains("3 tool calls"))
  }

  /** The same call twice means the model is ignoring an answer it already has. */
  @Test
  fun anIdenticalRepeatedCallIsNotRunAgain() = runTest {
    val clock = recordingTool("clock", "10:30")
    val backend =
      ScriptedBackend(
        listOf(
          calls(ToolInvocation("clock", emptyMap())),
          calls(ToolInvocation("clock", emptyMap())),
          says("Half past."),
        )
      )
    loop(backend, listOf(clock)).run(ask("time?")).toList()

    assertEquals("the tool should have run once, not twice", 1, clock.runs)
    val told = backend.seen[2].messages.last()
    assertTrue(told.text, told.text.contains("already ran this"))
  }

  /** Same tool, different arguments, is a different question and does run. */
  @Test
  fun thesameToolWithDifferentArgumentsRunsAgain() = runTest {
    val lookup = recordingTool("lookup", "found")
    val backend =
      ScriptedBackend(
        listOf(
          calls(ToolInvocation("lookup", mapOf("q" to "a"))),
          calls(ToolInvocation("lookup", mapOf("q" to "b"))),
          says("both found"),
        )
      )
    loop(backend, listOf(lookup)).run(ask("two things")).toList()
    assertEquals(2, lookup.runs)
  }

  // ---- failures ----

  @Test
  fun aToolThatThrowsIsReportedToTheModelRatherThanLosingTheTurn() = runTest {
    val exploding =
      object : AgentTool {
        override val name = "boom"
        override val description = "throws"
        override val parameters = emptyList<ToolParameter>()
        override val roles = AgentRole.entries.toSet()

        override suspend fun run(arguments: Map<String, Any?>): ToolResult =
          throw IllegalStateException("the disk is on fire")
      }
    val backend =
      ScriptedBackend(listOf(calls(ToolInvocation("boom", emptyMap())), says("I could not.")))

    val events = loop(backend, listOf(exploding)).run(ask("go")).toList()

    val blown = events.filterIsInstance<LoopEvent.ToolFinished>().single()
    assertEquals("boom", blown.name)
    assertFalse(blown.ok)
    assertTrue(blown.said, blown.said.contains("the disk is on fire"))
    assertEquals(LoopEvent.Done("I could not."), events.last())
    assertTrue(backend.seen[1].messages.last().text.contains("the disk is on fire"))
  }

  @Test
  fun aBackendFailureEndsTheTurnRatherThanLooping() = runTest {
    val backend = ScriptedBackend(listOf(listOf(TurnEvent.Failed("no model", "detail"))))
    val events = loop(backend).run(ask("hi")).toList()
    assertEquals(listOf(LoopEvent.Failed("no model", "detail")), events)
  }

  @Test
  fun aModelThatSaysNothingAtAllIsAFailureAndNotAHang() = runTest {
    val events = loop(ScriptedBackend(listOf(emptyList()))).run(ask("hi")).toList()
    assertTrue(events.single() is LoopEvent.Failed)
  }

  /** Done carries the whole reply, so it must replace the streamed tokens, not append to them. */
  @Test
  fun aStreamedReplyIsNotCountedTwice() = runTest {
    val backend =
      ScriptedBackend(
        listOf(
          listOf(TurnEvent.Token("Half "), TurnEvent.Token("past."), TurnEvent.Done("Half past."))
        )
      )
    assertEquals(LoopEvent.Done("Half past."), loop(backend).run(ask("time?")).toList().single())
  }

  // ---- helpers ----

  private fun loop(
    backend: LlmBackend,
    tools: List<AgentTool> = emptyList(),
    role: AgentRole = AgentRole.ORCHESTRATOR,
  ) = TurnLoop(backend, ToolRegistry(tools), role)

  private fun ask(text: String) = listOf(Message(Message.Role.USER, text))

  private fun says(text: String) = listOf(TurnEvent.Done(text))

  private fun calls(vararg invocations: ToolInvocation) =
    listOf(TurnEvent.ToolCalls(invocations.toList()))

  private fun saysAndCalls(text: String, vararg invocations: ToolInvocation) =
    listOf(TurnEvent.Token(text), TurnEvent.ToolCalls(invocations.toList()))

  private fun recordingTool(
    toolName: String,
    answer: String,
    roles: Set<AgentRole> = AgentRole.entries.toSet(),
  ) = RecordingTool(toolName, answer, roles)

  class RecordingTool(
    override val name: String,
    private val answer: String,
    override val roles: Set<AgentRole>,
  ) : AgentTool {
    override val description = "a tool for tests"
    override val parameters = emptyList<ToolParameter>()
    var runs = 0
      private set

    override suspend fun run(arguments: Map<String, Any?>): ToolResult {
      runs++
      return ToolResult.Ok(answer)
    }
  }

  /** A backend that replays a script, one list of events per exchange, recording what it saw. */
  class ScriptedBackend(private val script: List<List<TurnEvent>>) : LlmBackend {
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

  // ---- the model writing a tool call the engine cannot read ----

  /**
   * A tool call that would not parse is handed back to the model, once.
   *
   * Seen on a phone: Gemma emitted the formatter's own quote markers inside the arguments,
   * invented a parameter the tool does not have, and closed the block with the opening tag.
   * The turn died on it and the user got a page of parser internals. Nothing here can stop a
   * model writing that; what it can do is say so and let it write the call again.
   */
  @Test
  fun aMalformedToolCallIsHandedBackToTheModelOnce() = runTest {
    val backend =
      ScriptedBackend(
        listOf(
          listOf(
            TurnEvent.Failed(
              message = "The model tried to use a tool and wrote the request wrongly.",
              detail = "Failed to parse tool calls from code block: call:find_photo{...}",
            )
          ),
          says("It is a computer case."),
        )
      )

    val events = loop(backend).run(ask("describe the photo")).toList()

    assertEquals(listOf(LoopEvent.Done("It is a computer case.")), events)
    // And the second attempt was told what was wrong with the first, rather than being asked
    // the same question again and writing the same thing.
    assertEquals(MALFORMED_TOOL_CALL_NUDGE, backend.seen[1].messages.last().text)
  }

  @Test
  fun aSecondUnreadableToolCallIsReportedRatherThanRetriedAgain() = runTest {
    val unreadable =
      listOf(TurnEvent.Failed("wrote the request wrongly", "Failed to parse FC tool calls"))
    val backend = ScriptedBackend(listOf(unreadable, unreadable))

    val events = loop(backend).run(ask("describe the photo")).toList()

    assertTrue(events.single() is LoopEvent.Failed)
    // Twice, not three times: a third exchange is the same slow failure with the user watching.
    assertEquals(2, backend.seen.size)
  }

  @Test
  fun aFailureTheModelCannotFixIsNotRetried() = runTest {
    val backend =
      ScriptedBackend(
        listOf(listOf(TurnEvent.Failed("This model is too large for this phone's free memory.")))
      )

    val events = loop(backend).run(ask("hello")).toList()

    assertTrue(events.single() is LoopEvent.Failed)
    assertEquals(1, backend.seen.size)
  }

  /** The registry refuses two tools with one name, because one of them could never be called. */
  @Test
  fun twoToolsCannotShareAName() {
    val clash =
      runCatching { ToolRegistry(listOf(recordingTool("same", "a"), recordingTool("same", "b"))) }
    assertTrue(clash.isFailure)
    assertFalse(clash.exceptionOrNull()?.message.isNullOrEmpty())
  }
}
