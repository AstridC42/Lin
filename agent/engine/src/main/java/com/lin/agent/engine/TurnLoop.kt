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
import kotlinx.coroutines.flow.takeWhile

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
  /**
   * Tools that exist but must not be offered this turn, by name.
   *
   * For a tool whose availability is a property of the phone right now rather than of the
   * role - `delegate` with no server configured being the one this was written for. Offering
   * it anyway is not a cosmetic fault: a 2B model handed a tool called "delegate" reaches for
   * it on anything that smells like work, so "tell me a short story" spent one whole exchange
   * writing a brief, got "there is no server", and then generated the story locally in a
   * second exchange. Twice the wait, and a red card on screen, for nothing.
   *
   * Withheld from the list *and* from the lookup, so a model that remembers the tool from
   * earlier in the conversation cannot call it either.
   */
  private val withhold: Set<String> = emptySet(),
) {

  fun run(conversation: List<Message>): Flow<LoopEvent> = flow {
    val tools = registry.forRole(role).filterNot { it.name in withhold }.map { it.spec() }
    val messages = conversation.toMutableList()
    val alreadyRun = mutableSetOf<String>()
    var toolCalls = 0
    var toolCallRetries = 0
    var alreadyNudgedOutOfARut = false
    /** Numbers the calls of this turn, so a result can name the one it answers. */
    var callsSoFar = 0
    // Whether anything in this turn has produced an answer. Read when a tool fails - see
    // [toolFailureText] - because a failure arriving after a success must not become the reply.
    var somethingWorked = false

    while (true) {
      val outcome = oneExchange(messages, tools) { emit(it) }

      when (outcome) {
        is Exchange.Answered -> {
          emit(LoopEvent.Done(outcome.text))
          return@flow
        }
        is Exchange.Failed -> {
          // The one failure the model itself can fix: it wrote a tool call the engine could
          // not read. Telling it so and letting it write the call again costs one exchange
          // and usually works - throwing the turn away over a formatting slip does not.
          // Every other failure is terminal here, because retrying an out-of-memory or a
          // model that is still loading only fails again, more slowly.
          if (
            isMalformedToolCall(outcome.message, outcome.detail) &&
              toolCallRetries < budget.maxToolCallRetries
          ) {
            toolCallRetries++
            messages.add(Message(Message.Role.USER, MALFORMED_TOOL_CALL_NUDGE))
            continue
          }
          // The other failure the model can fix: it went round in circles and was cut off.
          // Told to stop thinking and answer, it usually does. Once only - a model that
          // loops twice is one that cannot answer this question, and the person should be
          // told that rather than watching it try a third time.
          if (outcome.message == STUCK && !alreadyNudgedOutOfARut) {
            alreadyNudgedOutOfARut = true
            messages.add(Message(Message.Role.USER, RUNAWAY_NUDGE))
            continue
          }
          emit(LoopEvent.Failed(outcome.message, outcome.detail))
          return@flow
        }
        is Exchange.WantsTools -> {
          // The turn that asked for the tools, written back into the conversation before
          // their results are. This is not bookkeeping: a tool result with no preceding
          // assistant tool-call turn is a result the model does not recognise as the answer
          // to anything, so it asks again until the budget stops it. See Message.toolCalls.
          //
          // Whatever it said on the way to asking rides on the same turn, so it is kept
          // rather than discarded when the tool result comes back.
          val asked = outcome.calls.map { it.copy(id = it.id.ifEmpty { "call_${++callsSoFar}" }) }
          messages.add(
            Message(Message.Role.ASSISTANT, outcome.spokenText, toolCalls = asked)
          )

          for (call in asked) {
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
                  // Still answers the call it was asked for. A tool message with no id is
                  // one an OpenAI-faithful server rejects outright.
                  toolCallId = call.id,
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
            if (result is ToolResult.Ok) somethingWorked = true
            val pictures = (result as? ToolResult.Ok)?.looksAt.orEmpty()
            val canSee = backend.capabilities.supportsImages
            messages.add(
              Message(
                Message.Role.TOOL,
                toolCallId = call.id,
                text = when (result) {
                  is ToolResult.Ok ->
                    // Said in the tool's own result rather than left for the model to work
                    // out. A model that is handed nothing and told nothing describes the
                    // picture anyway, from the search words that found it.
                    if (pictures.isNotEmpty() && !canSee) {
                      result.text + " This model cannot see pictures, so you have not been " +
                        "shown it - do not describe what is in it."
                    } else result.text
                  is ToolResult.Failed -> toolFailureText(result.message, somethingWorked)
                },
                toolName = call.name,
              )
            )

            // As a user-role message rather than on the tool result, because that is the role
            // every backend accepts a picture on: an OpenAI tool message takes a plain string.
            // Labelled, so the model does not read it as the person having said something.
            if (pictures.isNotEmpty() && canSee) {
              messages.add(
                Message(
                  Message.Role.USER,
                  "This is the picture ${call.name} found, for you to look at. The user did " +
                    "not say this.",
                  attachments = pictures,
                )
              )
            }
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
    // Thinking and speaking are watched together, because a model can go round in either and
    // the one seen on a phone was the thinking channel - which does not even reach the reply.
    val everything = StringBuilder()
    var inARut = false
    var sinceLastCheck = 0

    backend
      .converse(
        TurnRequest(
          // Trimmed here rather than where the conversation is held, because this is where it
          // grows fastest: a turn with four tool calls adds four results to the same request,
          // and the overflow arrives mid-turn rather than mid-conversation.
          messages = fitToContext(messages, backend.capabilities.contextWindow),
          systemPrompt = systemPrompt,
          tools = tools,
          reasoning = reasoning,
        )
      )
      .takeWhile { !inARut }
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
        // Checked after every event rather than at the end, because the end is exactly what
        // does not arrive: a model in a rut generates until the server's own limit, which on
        // an unconfigured server is a very long way away.
        if (event is TurnEvent.Token || event is TurnEvent.Thought) {
          val text = if (event is TurnEvent.Token) event.text else (event as TurnEvent.Thought).text
          everything.append(text)
          // Not on every token. The check searches a window of several thousand characters,
          // and a model streaming a word at a time would pay for that hundreds of times a
          // second. A rut runs for thousands of characters, so looking every few hundred
          // catches it just as surely and costs nothing noticeable.
          sinceLastCheck += text.length
          if (sinceLastCheck >= CHECK_EVERY) {
            sinceLastCheck = 0
            if (looksLikeARut(everything.toString())) inARut = true
          }
        }
      }

    failure?.let {
      return it
    }
    // A rut is reported as its own failure so the loop can tell it apart from every other
    // one and hand the model a note that tells it to stop deliberating - see RUNAWAY_NUDGE.
    if (inARut) return Exchange.Failed(STUCK, "The model repeated itself and was stopped.")
    if (calls.isNotEmpty()) return Exchange.WantsTools(calls.toList(), spoken.toString())
    if (done || spoken.isNotEmpty()) return Exchange.Answered(spoken.toString())
    return Exchange.Failed("The model stopped without answering.")
  }

  private suspend fun runTool(call: ToolInvocation): ToolResult {
    val tool =
      registry.find(role, call.name)?.takeIf { it.name !in withhold }
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

    /** How many characters may arrive between one check for a rut and the next. */
    const val CHECK_EVERY = 200
  }
}
