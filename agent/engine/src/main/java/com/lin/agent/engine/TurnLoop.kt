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

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the screen sees while a turn runs. Tool activity is shown, not hidden. */
sealed interface LoopEvent {
  data class Token(val text: String) : LoopEvent

  /**
   * A fragment of the model's thinking, kept apart from the reply all the way up.
   *
   * It reaches the screen because thinking you cannot see is not something you can be said to
   * control - and because a minute of silence before the first word is indistinguishable from a
   * hung app, which is the same reason tool activity is shown. It is never added to [messages]:
   * what goes back to the model is what it said, not what it considered.
   */
  data class Thought(val text: String) : LoopEvent

  /** A tool is about to run. Worth showing: it is the difference between slow and stuck. */
  data class ToolStarted(val name: String) : LoopEvent

  /** [shows] is anything the tool produced for the screen rather than for the model. */
  data class ToolFinished(
    val name: String,
    val ok: Boolean,
    val shows: List<String> = emptyList(),
  ) : LoopEvent

  data class Done(val text: String) : LoopEvent

  data class Failed(val message: String, val detail: String = "") : LoopEvent
}

/**
 * One turn: a message in, tool calls run as the model asks for them, a reply out.
 *
 * The loop is explicit rather than left to the engine's own automatic tool calling, for three
 * reasons that all turned out to matter. Tools are looked up per role, so a sub-agent cannot
 * reach an orchestrator's; the budget is enforced here, where it can be counted; and tool
 * activity is visible to the screen, because a model that has quietly gone round four times is
 * indistinguishable from a hung app otherwise.
 *
 * The important property, and the one the tests pin: **a backend that never reports a tool call
 * still holds an ordinary conversation.** Tool calling is the part of this that cannot be
 * verified without a device, so it is arranged to be additive - if no call ever arrives, this is
 * a passthrough, and plain chat cannot be broken by a tool-calling bug.
 */
class TurnLoop(
  private val backend: LlmBackend,
  private val registry: ToolRegistry,
  private val role: AgentRole,
  private val budget: Budget = Budget(),
  private val systemPrompt: String = "",
  /** How much the model may think per exchange. The user's setting, passed straight through. */
  private val reasoning: Reasoning = Reasoning.OFF,
) {

  fun run(conversation: List<Message>): Flow<LoopEvent> = flow {
    val tools = registry.forRole(role).map { it.spec() }
    val messages = conversation.toMutableList()
    val alreadyRun = mutableSetOf<String>()
    var toolCalls = 0

    while (true) {
      val outcome = oneExchange(messages, tools) { emit(it) }

      when (outcome) {
        is Exchange.Answered -> {
          emit(LoopEvent.Done(outcome.text))
          return@flow
        }
        is Exchange.Failed -> {
          emit(LoopEvent.Failed(outcome.message, outcome.detail))
          return@flow
        }
        is Exchange.WantsTools -> {
          // Whatever it said on the way to asking is still part of the reply, so it is kept
          // rather than discarded when the tool result comes back.
          if (outcome.spokenText.isNotBlank()) {
            messages.add(Message(Message.Role.ASSISTANT, outcome.spokenText))
          }

          for (call in outcome.calls) {
            if (toolCalls >= budget.maxToolCalls) {
              emit(
                LoopEvent.Failed(
                  "This is taking more steps than it should. Nothing further was run.",
                  "Stopped after ${budget.maxToolCalls} tool calls.",
                )
              )
              return@flow
            }
            toolCalls++

            // The same call twice means the model is not using the answer it already has.
            // Letting it continue burns the budget on a loop; saying so gives it a way out.
            val fingerprint = "${call.name}(${call.arguments})"
            if (!alreadyRun.add(fingerprint)) {
              messages.add(
                Message(
                  Message.Role.TOOL,
                  "You already ran this and got an answer above. Use it, or answer without it.",
                  toolName = call.name,
                )
              )
              continue
            }

            emit(LoopEvent.ToolStarted(call.name))
            val result = runTool(call)
            emit(
              LoopEvent.ToolFinished(
                name = call.name,
                ok = result is ToolResult.Ok,
                shows = (result as? ToolResult.Ok)?.shows.orEmpty(),
              )
            )
            messages.add(
              Message(
                Message.Role.TOOL,
                when (result) {
                  is ToolResult.Ok -> result.text
                  is ToolResult.Failed -> "That did not work: ${result.message}"
                },
                toolName = call.name,
              )
            )
          }
        }
      }
    }
  }

  /** One round trip to the model, reduced to what the loop decides on. */
  private suspend fun oneExchange(
    messages: List<Message>,
    tools: List<ToolSpec>,
    show: suspend (LoopEvent) -> Unit,
  ): Exchange {
    val spoken = StringBuilder()
    val calls = mutableListOf<ToolInvocation>()
    var failure: Exchange.Failed? = null
    var done = false

    backend
      .converse(
        TurnRequest(
          messages = messages,
          systemPrompt = systemPrompt,
          tools = tools,
          reasoning = reasoning,
        )
      )
      .collect { event ->
        when (event) {
          is TurnEvent.Token -> spoken.append(event.text)
          // Forwarded as it arrives rather than collected: the point of showing thinking is
          // that something is visibly happening during the wait it causes.
          is TurnEvent.Thought -> show(LoopEvent.Thought(event.text))
          is TurnEvent.ToolCalls -> calls.addAll(event.calls)
          is TurnEvent.Done -> {
            done = true
            // Done carries the whole reply, so it replaces the tokens rather than adding to
            // them - otherwise every answer arrives twice over.
            spoken.setLength(0)
            spoken.append(event.fullText)
          }
          is TurnEvent.Failed -> failure = Exchange.Failed(event.message, event.detail)
        }
      }

    failure?.let {
      return it
    }
    if (calls.isNotEmpty()) return Exchange.WantsTools(calls.toList(), spoken.toString())
    if (done || spoken.isNotEmpty()) return Exchange.Answered(spoken.toString())
    return Exchange.Failed("The model stopped without answering.")
  }

  private suspend fun runTool(call: ToolInvocation): ToolResult {
    val tool =
      registry.find(role, call.name)
        // Named something it was never offered. Told plainly, because the alternative is the
        // model trying the same unavailable tool until the budget runs out.
        ?: return ToolResult.Failed(
          "There is no tool called \"${call.name}\". Use one of the tools listed, or answer " +
            "without a tool."
        )

    return try {
      tool.run(call.arguments)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Throwable) {
      // A throwing tool is a bug in the tool, not a reason to lose the conversation. The model
      // is told it failed and can carry on or say it could not.
      Log.w(TAG, "Tool ${call.name} threw", e)
      ToolResult.Failed(e.message ?: e::class.java.simpleName)
    }
  }

  private sealed interface Exchange {
    data class Answered(val text: String) : Exchange

    data class WantsTools(val calls: List<ToolInvocation>, val spokenText: String) : Exchange

    data class Failed(val message: String, val detail: String = "") : Exchange
  }

  private companion object {
    const val TAG = "LinTurnLoop"
  }
}
