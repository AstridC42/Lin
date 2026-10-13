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

/**
 * Who is holding the conversation.
 *
 * Two of them, and the distinction is the point. The orchestrator is resident on the phone, owns
 * the conversation, and has a handful of tools. A sub-agent is created for one task, runs
 * wherever it is cheapest, and sees only the tools that task needs.
 */
enum class AgentRole {
  ORCHESTRATOR,
  SUB_AGENT,
}

/** What a tool takes. Kept to the shapes a small model reliably fills. */
enum class ParamType {
  STRING,
  INTEGER,
  BOOLEAN,
  STRING_LIST,
}

data class ToolParameter(
  val name: String,
  val type: ParamType,
  val description: String,
  val required: Boolean = true,
)

/** How a tool call ended. [Failed] is a message for the model, not an exception for the app. */
sealed interface ToolResult {
  /**
   * [text] is what the model is told. [shows] is what the user is shown.
   *
   * Two channels because a photo search has nothing useful to say in words - "I found three
   * photos" is not an answer to "find the one from the beach" - and describing an image back to
   * a model that cannot see it only invites it to embellish. The pictures go to the screen; the
   * model gets told how many and that they are already visible.
   */
  data class Ok(val text: String, val shows: List<String> = emptyList()) : ToolResult

  data class Failed(val message: String) : ToolResult
}

/**
 * Something the model can ask to have done.
 *
 * [roles] is the structural fix this whole file exists for. The app this replaces assembled one
 * prompt containing forty-four tools for every conversation, and keeping that list appropriate
 * was a matter of discipline - which is to say, it was not kept. Here a tool states who may see
 * it, and the only way to get a tool list is to ask for a role's, so the wrong prompt is not
 * something you have to remember not to build.
 */
interface AgentTool {
  val name: String

  /** One line. It is read by a 2B model, not by a documentation generator. */
  val description: String

  val parameters: List<ToolParameter>

  val roles: Set<AgentRole>

  suspend fun run(arguments: Map<String, Any?>): ToolResult
}

/**
 * Every tool the app has, answerable by role.
 *
 * There is deliberately no "all tools" accessor. Every way out of here takes a role, so a prompt
 * cannot be built from the whole set by accident - which is exactly how the previous app's
 * forty-four-tool prompt happened.
 */
class ToolRegistry(tools: List<AgentTool>) {

  private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

  init {
    require(byName.size == tools.size) {
      val duplicates = tools.map { it.name }.groupBy { it }.filterValues { it.size > 1 }.keys
      "Two tools share a name, so one of them can never be called: $duplicates"
    }
  }

  /** What [role] may see, in a stable order so the prompt does not change between turns. */
  fun forRole(role: AgentRole): List<AgentTool> =
    byName.values.filter { role in it.roles }.sortedBy { it.name }

  /**
   * The tool [role] may call by this name, or null.
   *
   * Scoped by role rather than looked up globally and checked afterwards. A model naming a tool
   * it was never offered is not an error worth distinguishing from a tool that does not exist -
   * both mean "you cannot do that" - and collapsing them here means there is no version of this
   * lookup that returns something the caller is not allowed to run.
   */
  fun find(role: AgentRole, name: String): AgentTool? =
    byName[name]?.takeIf { role in it.roles }
}

/**
 * A tool as the model is told about it.
 *
 * Separate from [AgentTool] because the backends need a wire format and not a Kotlin object -
 * LiteRT-LM takes an OpenAPI description string, Ollama takes the same shape as JSON in the
 * request body, and both are the same JSON Schema underneath.
 */
data class ToolSpec(val name: String, val description: String, val parametersSchema: String)

/** What the model asked to run. Arguments are untrusted: it invents them. */
data class ToolInvocation(val name: String, val arguments: Map<String, Any?>)

/** [this] as the backends need to advertise it. */
fun AgentTool.spec(): ToolSpec =
  ToolSpec(name = name, description = description, parametersSchema = parametersSchemaOf(parameters))

/** JSON Schema for a tool's arguments, which is what both backends want. */
internal fun parametersSchemaOf(parameters: List<ToolParameter>): String {
  val properties = JSONObject()
  for (parameter in parameters) {
    properties.put(
      parameter.name,
      JSONObject().put("description", parameter.description).apply {
        when (parameter.type) {
          ParamType.STRING -> put("type", "string")
          ParamType.INTEGER -> put("type", "integer")
          ParamType.BOOLEAN -> put("type", "boolean")
          ParamType.STRING_LIST -> {
            put("type", "array")
            put("items", JSONObject().put("type", "string"))
          }
        }
      },
    )
  }
  return JSONObject()
    .put("type", "object")
    .put("properties", properties)
    .put(
      "required",
      parameters.filter { it.required }.map { it.name }.let { names ->
        org.json.JSONArray().also { array -> names.forEach(array::put) }
      },
    )
    .toString()
}
