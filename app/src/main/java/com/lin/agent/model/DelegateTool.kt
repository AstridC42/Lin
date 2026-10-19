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

package com.lin.agent.model

import android.util.Log
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.Brief
import com.lin.agent.engine.LlmBackend
import com.lin.agent.engine.Message
import com.lin.agent.engine.OutputShape
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.engine.TurnEvent
import com.lin.agent.engine.TurnRequest
import kotlinx.coroutines.flow.toList

/**
 * Hands one task to the bigger model on the server, and brings the answer back.
 *
 * The orchestrator's reason to exist. A 2B model on a phone is good at holding a conversation and
 * knowing what is being asked; it is not what you want writing a long analysis. This is where the
 * difference gets used.
 *
 * The sub-agent is given a [Brief] rather than the conversation, and that is deliberate: it
 * cannot see what was said, so everything it needs has to be stated. The risk this creates is the
 * one the whole design turns on - a restatement that quietly drops the condition that mattered -
 * which is why `constraints` is its own argument with its own instruction rather than something
 * to be folded into the goal.
 *
 * No tools are offered to the sub-agent. It answers from the model, and that is the whole of its
 * job for now; giving it tools is a decision with its own failure modes and it has not been made.
 */
class DelegateTool(private val subAgent: suspend () -> LlmBackend?) : AgentTool {

  override val name = NAME

  override val description =
    "Hand one self-contained task to a larger model on the user's own server, and get its " +
      "answer back. Worth it for research, long writing or heavy analysis. Not worth it for " +
      "anything you can answer yourself."

  override val parameters =
    listOf(
      ToolParameter(
        name = "goal",
        type = ParamType.STRING,
        description = "The single task to carry out, stated so someone who has not read this " +
          "conversation could do it.",
      ),
      ToolParameter(
        name = "constraints",
        type = ParamType.STRING_LIST,
        description = "Every condition the answer must meet, copied from what the user actually " +
          "said - limits, preferences, language, anything they ruled out. Leave empty only if " +
          "they stated none.",
        required = false,
      ),
      ToolParameter(
        name = "expects",
        type = ParamType.STRING,
        description = "One of SHORT_ANSWER, LIST, DRAFT_TEXT, PLAN.",
        required = false,
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val goal = (arguments["goal"] as? String)?.trim().orEmpty()
    if (goal.isEmpty()) {
      return ToolResult.Failed("No goal was given, so there was nothing to hand over.")
    }

    val brief =
      Brief(
        goal = goal,
        constraints = constraintsFrom(arguments["constraints"]),
        expects =
          OutputShape.from((arguments["expects"] as? String).orEmpty()) ?: OutputShape.SHORT_ANSWER,
      )

    val backend =
      subAgent()
        // Said in terms the orchestrator can act on: it should answer the user itself rather
        // than apologise about a server they may not know exists.
        ?: return ToolResult.Failed(
          "There is no server model available, so this could not be handed over. Answer it " +
            "yourself as well as you can."
        )

    return try {
      collect(backend, brief)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.w(TAG, "Delegation failed", e)
      ToolResult.Failed(e.message ?: e::class.java.simpleName)
    }
  }

  private suspend fun collect(backend: LlmBackend, brief: Brief): ToolResult {
    val events =
      backend
        .converse(
          TurnRequest(
            messages = listOf(Message(Message.Role.USER, brief.asInstruction())),
            systemPrompt = SUB_AGENT_PROMPT,
          )
        )
        .toList()

    events.filterIsInstance<TurnEvent.Failed>().firstOrNull()?.let {
      return ToolResult.Failed(it.message)
    }

    val answer =
      events.filterIsInstance<TurnEvent.Done>().lastOrNull()?.fullText
        ?: events.filterIsInstance<TurnEvent.Token>().joinToString("") { it.text }

    return if (answer.isBlank()) ToolResult.Failed("The server returned nothing.")
    else ToolResult.Ok(answer)
  }

  /**
   * The constraints argument, whatever shape the model put it in.
   *
   * A list is what the schema asks for and a string is what a small model sometimes sends
   * anyway. Taking both costs four lines; refusing the string would throw away the exact thing
   * this argument exists to carry.
   */
  private fun constraintsFrom(value: Any?): List<String> =
    when (value) {
      is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
      is String -> value.split('\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
      else -> emptyList()
    }

  companion object {
    /**
     * Named here rather than only on the instance, because the turn loop withholds this tool
     * by name when there is no server - see ConversationViewModel - and a string typed twice
     * is a string that gets changed once.
     */
    const val NAME = "delegate"

    private const val TAG = "LinDelegateTool"

    /** Short on purpose: a sub-agent with one task does not need steering, it needs the task. */
    private const val SUB_AGENT_PROMPT =
      "You are given one task by another assistant. Do exactly it. The conditions listed are " +
        "binding - an answer that breaks one is wrong however good it otherwise is. Reply with " +
        "the answer only: no preamble, no restating the task, no offers of further help."
  }
}
