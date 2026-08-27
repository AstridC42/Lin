/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.assistant

/**
 * Builds the system prompt for the unified assistant.
 *
 * The prompt is assembled from four parts:
 * 1. a fixed persona and set of house rules,
 * 2. the capability catalogue, which lists only the tools that are actually wired up right now
 *    (web search disappears when no SearXNG instance is configured, the MCP section disappears
 *    when no MCP tool is enabled),
 * 3. the `___SKILLS___` and `___TOOLS___` placeholders, which
 *    [com.google.ai.edge.gallery.agent.PromptExpander] fills in with the live skill and MCP lists,
 * 4. the current [AssistantMode] block.
 *
 * Generated prompts open with a marker line carrying a fingerprint of the body that follows. That
 * is what lets the app tell "the user has never touched the system prompt, so rebuild it when the
 * mode or the tool set changes" apart from "the user edited it, so leave it alone" - editing any
 * character of the body breaks the fingerprint, so a hand-edited prompt is never silently
 * regenerated.
 */
object AssistantPrompts {
  /** Prefix of the marker line that opens every generated prompt. */
  private const val MARKER_PREFIX = "<!-- star-storyteller-assistant:"

  private const val MARKER_SUFFIX = " -->"

  /** Substitution key filled in with the list of installed skills. */
  const val SKILLS_PLACEHOLDER = "___SKILLS___"

  /** Substitution key filled in with the list of enabled MCP tools. */
  const val TOOLS_PLACEHOLDER = "___TOOLS___"

  /** Heading that opens the mode block at the end of every prompt. */
  private const val MODE_HEADING = "=== MODE ==="

  /**
   * Whether [prompt] is one this object produced and nobody has edited since.
   *
   * A prompt whose body no longer matches the fingerprint in its marker line counts as the user's
   * own, and is preserved.
   */
  fun isGenerated(prompt: String): Boolean {
    val trimmed = prompt.trimStart()
    if (!trimmed.startsWith(MARKER_PREFIX)) return false
    val markerEnd = trimmed.indexOf('\n')
    if (markerEnd < 0) return false
    val marker = trimmed.substring(0, markerEnd).trimEnd()
    if (!marker.endsWith(MARKER_SUFFIX)) return false
    val declaredFingerprint =
      marker.removePrefix(MARKER_PREFIX).removeSuffix(MARKER_SUFFIX).trim()
    return declaredFingerprint == fingerprintOf(trimmed.substring(markerEnd + 1))
  }

  /** A short, stable fingerprint of the prompt body. Not security-sensitive - only change detection. */
  private fun fingerprintOf(body: String): String =
    java.lang.Integer.toHexString(body.hashCode())

  /**
   * Assembles the full system prompt.
   *
   * @param mode the mode whose block is appended at the end.
   * @param hasMcpTools whether at least one MCP tool is enabled; when false the MCP section and
   *   the `___TOOLS___` placeholder are left out so the model is not told about tools it cannot
   *   call.
   * @param webSearchEnabled whether a SearXNG instance is configured and switched on.
   * @param webSearchInstance host of that instance, shown to the model for transparency.
   */
  fun build(
    mode: AssistantMode,
    hasMcpTools: Boolean,
    webSearchEnabled: Boolean,
    webSearchInstance: String = "",
    smartHomeEnabled: Boolean = false,
  ): String {
    val body =
      buildBody(mode, hasMcpTools, webSearchEnabled, webSearchInstance, smartHomeEnabled)
    return "$MARKER_PREFIX${fingerprintOf(body)}$MARKER_SUFFIX\n$body"
  }

  /**
   * Returns the prompt to actually use.
   *
   * A prompt the user has edited by hand is theirs: only the mode block at the end is replaced, so
   * switching mode does not throw away their wording. A generated one is rebuilt from scratch, so
   * turning web search or smart home on adds its section.
   */
  fun resolve(
    currentPrompt: String,
    mode: AssistantMode,
    hasMcpTools: Boolean,
    webSearchEnabled: Boolean,
    webSearchInstance: String = "",
    smartHomeEnabled: Boolean = false,
  ): String =
    if (currentPrompt.isBlank() || isGenerated(currentPrompt)) {
      build(
        mode = mode,
        hasMcpTools = hasMcpTools,
        webSearchEnabled = webSearchEnabled,
        webSearchInstance = webSearchInstance,
        smartHomeEnabled = smartHomeEnabled,
      )
    } else {
      buildString {
        // Drop a mode block appended by an earlier call, or switching mode would stack them: the
        // prompt shown in the editor is the resolved one, and saving it feeds it back in here.
        append(currentPrompt.substringBefore(MODE_HEADING).trimEnd())
        appendLine()
        appendLine()
        appendLine(MODE_HEADING)
        appendLine()
        append(mode.promptFragment.trimIndent())
      }
    }

  /**
   * The prompt body.
   *
   * Kept short on purpose, and measured. An earlier version ran to roughly 1,800 tokens before the
   * skill list was even substituted in. With ~1,300 tokens of tool descriptions alongside it, a
   * model whose whole budget is 4,000 had almost nothing left for the conversation, and the first
   * tool result that returned a list pushed it over - which is what "problem with the default max
   * tokens" and the crash on any phone action were.
   *
   * So every section here earns its place, and a section for a capability that is switched off is
   * left out entirely rather than described and then disclaimed.
   */
  private fun buildBody(
    mode: AssistantMode,
    hasMcpTools: Boolean,
    webSearchEnabled: Boolean,
    webSearchInstance: String,
    smartHomeEnabled: Boolean,
  ): String = buildString {
    appendLine(PERSONA.trimIndent())
    appendLine()
    appendLine(ROUTING.trimIndent())
    appendLine()
    // Directly under the routing step that names it. A small model reads the top of its prompt and
    // acts; a skill list three sections further down is a list it has stopped looking for.
    appendLine(SKILLS_HEADING)
    appendLine(SKILLS_PLACEHOLDER)
    appendLine()
    if (webSearchEnabled) {
      appendLine(
        "WEB: webSearch(query), then openWebPage(url) for detail, via ${
          webSearchInstance.ifBlank { "the user's SearXNG instance" }
        }. showImageFromWeb(query) puts a picture in the chat; showWebPageInChat(url) puts a " +
          "page there to read. Both display it already - say one line about it, do not describe " +
          "it at length or repeat the URL. Cite sources. Never present a guess as a result."
      )
      appendLine()
    }
    if (smartHomeEnabled) {
      appendLine(
        "HOME: listSmartHomeDevices(filter) for exact ids, then setSmartHomeDevice(id, on) or " +
          "setSmartHomeLevel(id, level). Ask which one if a name is ambiguous - turning off the " +
          "wrong thing in someone's house is not a small mistake."
      )
      appendLine()
    }
    if (hasMcpTools) {
      appendLine("MCP TOOLS: call with runMcpTool(toolName, input), using the exact name.")
      appendLine(TOOLS_PLACEHOLDER)
      appendLine()
    }
    appendLine(HOUSE_RULES.trimIndent())
    appendLine()
    appendLine(MODE_HEADING)
    appendLine()
    append(mode.promptFragment.trimIndent())
  }

  private const val PERSONA =
    """
    You are Star Storyteller, the user's assistant. You run on their phone. Be brief and concrete -
    two or three sentences unless the answer is genuinely a list.
    """

  /**
   * The routing procedure.
   *
   * Written as an ordered list of imperatives because a small model follows the top of its prompt
   * and stops reading for intent. An earlier version led with "answer from what you know", and the
   * model did exactly that: asked to check Wikipedia, with the skill installed and its tools
   * registered, it answered that it could not. Answering from memory is the last step, not the
   * first.
   */
  private const val ROUTING =
    """
    For EVERY request, take the first of these that applies, silently:
    1. SKILL - if anything in the SKILLS list below covers it, make that exact load_skill call
       first, then follow the instructions it returns. Looking things up, calendars, email, QR
       codes, maps and calculations are almost always skills. Read the list before saying you
       cannot do something, and before answering from memory.
    2. PHONE - calls, messages, calendar, alarms, reminders, apps, directions, weather, music,
       torch, settings, notifications, and anything playing. Use the matching tool.
    3. ANSWER - only if nothing above fits. Say so when unsure rather than inventing detail.
    Never claim you are unable to do something before checking 1 and 2.
    """

  private const val SKILLS_HEADING =
    "SKILLS - make the call shown, then follow the instructions it returns exactly:"

  private const val HOUSE_RULES =
    """
    RULES
    1. Handle the whole message - an attachment and an instruction, or two instructions, is all of
       it before you reply.
    2. Do not narrate your tool calls. Work, then answer.
    3. Say what actually happened. A tool that failed, or only staged something for the user to
       confirm, is not "done".
    4. A call, a text or an email is prepared for the user to send. Say it is ready and waiting.
       A reply to a notification is the exception - it sends immediately, so read it back first.
    5. Never invent a search result, a number, a date or a price.
    """
}
