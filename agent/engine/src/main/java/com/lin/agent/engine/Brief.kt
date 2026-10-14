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

import org.json.JSONArray
import org.json.JSONObject

/** What the answer should look like, so a sub-agent does not return an essay for a yes or no. */
enum class OutputShape {
  SHORT_ANSWER,
  LIST,
  DRAFT_TEXT,
  PLAN;

  companion object {
    fun from(text: String): OutputShape? =
      entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
  }
}

/** What a delegated task is allowed to spend. A sub-agent with no ceiling is a hung app. */
data class Budget(
  val maxSeconds: Int = DEFAULT_SECONDS,
  val maxToolCalls: Int = DEFAULT_CALLS,
  /**
   * How many times a turn may be given back to the model after it wrote a tool call the
   * engine could not read. One: the second attempt usually works, and a third would be the
   * same slow exchange failing the same way with the user watching.
   */
  val maxToolCallRetries: Int = DEFAULT_TOOL_CALL_RETRIES,
) {
  companion object {
    const val DEFAULT_SECONDS = 120
    const val DEFAULT_CALLS = 6
    const val DEFAULT_TOOL_CALL_RETRIES = 1
  }
}

/**
 * The delegation payload: what the orchestrator hands a sub-agent.
 *
 * The highest-risk component in the whole design, and the reason it is a schema rather than a
 * sentence. Delegation means a 2B model restating somebody's request for a model that cannot see
 * the conversation, and the characteristic failure of every multi-agent system is that the
 * restatement drops the one condition that mattered - "under £50", "not on a Tuesday", "in
 * French". A paragraph loses it silently. A list of constraints that is its own field, filled
 * under constrained decoding, at least has somewhere for it to go.
 *
 * `context` from the design sketch is deliberately absent: it refers to memory, which does not
 * exist yet. Adding an empty field now would only invite something to be put in it.
 */
data class Brief(
  val goal: String,
  val constraints: List<String> = emptyList(),
  val expects: OutputShape = OutputShape.SHORT_ANSWER,
  val budget: Budget = Budget(),
) {

  /** The brief as the sub-agent is told it, with the constraints impossible to skim past. */
  fun asInstruction(): String = buildString {
    append("Task: ")
    append(goal)
    if (constraints.isNotEmpty()) {
      append("\n\nThese conditions are binding. An answer that breaks one is wrong, ")
      append("however good it is otherwise:")
      constraints.forEach { append("\n- ").append(it) }
    }
    append("\n\nAnswer as: ")
    append(
      when (expects) {
        OutputShape.SHORT_ANSWER -> "one or two sentences, no preamble."
        OutputShape.LIST -> "a short list, one item per line, no preamble."
        OutputShape.DRAFT_TEXT -> "the draft itself and nothing else - no commentary around it."
        OutputShape.PLAN -> "numbered steps, each one an action."
      }
    )
  }

  companion object {

    /**
     * The JSON Schema the orchestrator fills, for `ResponseFormat.json`.
     *
     * Constrained decoding is what makes this work on a 2B model: it fills slots rather than
     * writing prose that then has to be parsed back. `constraints` is required even when empty,
     * so that leaving it out is not a shortcut the decoder can take.
     */
    val SCHEMA: String =
      JSONObject()
        .put("type", "object")
        .put(
          "properties",
          JSONObject()
            .put(
              "goal",
              JSONObject()
                .put("type", "string")
                .put("description", "The single task to carry out, stated plainly."),
            )
            .put(
              "constraints",
              JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "string"))
                .put(
                  "description",
                  "Every condition the answer must satisfy, copied from what the user actually " +
                    "said. Empty only if they stated none.",
                ),
            )
            .put(
              "expects",
              JSONObject()
                .put("type", "string")
                .put("enum", JSONArray().also { OutputShape.entries.forEach { e -> it.put(e.name) } })
                .put("description", "The shape of the answer wanted."),
            ),
        )
        .put("required", JSONArray().put("goal").put("constraints").put("expects"))
        .toString()

    /**
     * Reads a brief the model produced, or says why it cannot be used.
     *
     * Strict about the goal and forgiving about everything else, which is the right asymmetry: a
     * brief with no goal is not a task and must not be sent anywhere, while an unrecognised
     * output shape only costs some formatting. Constraints that are not strings are dropped
     * individually rather than failing the brief - losing one is bad, losing the task is worse.
     */
    fun parse(json: String, budget: Budget = Budget()): BriefResult {
      val root =
        runCatching { JSONObject(json) }.getOrNull()
          ?: return BriefResult.Unusable("The brief was not JSON.")

      val goal = root.optString("goal").trim()
      if (goal.isEmpty()) return BriefResult.Unusable("The brief named no task.")

      val constraints = mutableListOf<String>()
      root.optJSONArray("constraints")?.let { array ->
        for (index in 0 until array.length()) {
          val value = array.optString(index).trim()
          if (value.isNotEmpty()) constraints.add(value)
        }
      }

      val expects = OutputShape.from(root.optString("expects")) ?: OutputShape.SHORT_ANSWER

      return BriefResult.Usable(
        Brief(goal = goal, constraints = constraints, expects = expects, budget = budget)
      )
    }
  }
}

sealed interface BriefResult {
  data class Usable(val brief: Brief) : BriefResult

  /** [reason] is shown; a brief that cannot be read is never guessed at. */
  data class Unusable(val reason: String) : BriefResult
}
