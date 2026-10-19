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
   *
   * [looksAt] is the third: pictures for the model itself, now that it can be given one. It is
   * separate from [shows] because they answer different questions. Everything found goes on
   * screen, because the person can take in six thumbnails at a glance; at most one goes to the
   * model, because each is a slice of a context window that has to hold the conversation too.
   * The loop drops these when the model cannot see - see [LoopEvent].
   */
  data class Ok(
    val text: String,
    val shows: List<String> = emptyList(),
    val looksAt: List<Attachment.Picture> = emptyList(),
  ) : ToolResult

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

  /**
   * The JSON Schema the model is given for this tool's arguments.
   *
   * Derived from [parameters] for every tool the app itself defines, which is why those
   * declare a list rather than writing schemas by hand. An MCP tool overrides it: the server
   * sent a complete JSON Schema, and flattening that into [ToolParameter] and building it
   * back up would lose nesting, enums and defaults that the server meant the model to see.
   */
  val parametersSchema: String
    get() = parametersSchemaOf(parameters)

  val roles: Set<AgentRole>

  /**
   * A sentence or two of prompt, about how to use this tool and what not to claim about it.
   *
   * Separate from [description], which tells the model what the tool is for and goes in the
   * schema. This is the behaviour around it: that a staged action is not a done one, that a
   * result has to be read before answering, that a tool's absence is not an invitation to
   * explain how the user could do it themselves.
   *
   * It lives on the tool because the alternative had already failed. The system prompt was a
   * hand-written constant listing every tool and how to treat it, and by the time it was
   * replaced it still said Lin could not change Wi-Fi - which Shizuku had made untrue - and
   * said nothing at all about the six tools added after it was written. A prompt assembled
   * from the tools that are switched on cannot drift from them.
   *
   * Empty by default, which is a fine answer: a tool whose description is enough needs no
   * second paragraph competing for the model's attention.
   */
  val guidance: String
    get() = ""

  suspend fun run(arguments: Map<String, Any?>): ToolResult
}

/**
 * The capability half of a system prompt, built from the tools actually on offer.
 *
 * [tools] is what the turn will really be given - after the role filter and after anything
 * the user switched off - so the prompt can neither promise a tool that is not there nor stay
 * silent about one that is.
 *
 * Sorted by name, like the registry's own listing, so the prompt is byte-identical between
 * turns that offer the same tools. That is not tidiness: an unchanged prefix is what lets a
 * server reuse its cache of it, and a prompt that reshuffled itself every turn would throw
 * that away on every question.
 */
fun capabilityPrompt(tools: List<AgentTool>): String {
  if (tools.isEmpty()) {
    return "You have no tools at all in this conversation. Answer from what you know, and " +
      "say plainly when you cannot do something rather than describing how the user could."
  }
  val said = tools.sortedBy { it.name }.mapNotNull { it.guidance.trim().takeIf(String::isNotEmpty) }
  return buildString {
    append("Answer directly whenever you can. ")
    said.forEach { append(it.trimEnd()).append(' ') }
    append(
      "Those tools are everything you can do to this phone: if something is not among them, " +
        "say you cannot do it rather than explaining how the user could."
    )
  }
}

/**
 * Every tool the app has, answerable by role.
 *
 * There is deliberately no "all tools" accessor. Every way out of here takes a role, so a prompt
 * cannot be built from the whole set by accident - which is exactly how the first version's
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
data class ToolInvocation(
  val name: String,
  val arguments: Map<String, Any?>,
  /**
   * What this call is called, for matching a result to it.
   *
   * Empty until the loop assigns one. OpenAI requires a tool result to name the call it
   * answers and rejects the request otherwise; Ollama does not use ids at all. Minted by the
   * loop rather than taken from the server, because a server that sends none still needs the
   * pair to be matchable on the way back.
   *
   * Deliberately not part of what makes two calls the same: see [fingerprintOf], which
   * compares the name and the arguments, because a model asking twice for the same thing has
   * asked twice whatever the ids are.
   */
  val id: String = "",
)

/** [this] as the backends need to advertise it. */
fun AgentTool.spec(): ToolSpec =
  ToolSpec(name = name, description = description, parametersSchema = parametersSchema)

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
