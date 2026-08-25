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
  ): String {
    val body = buildBody(mode, hasMcpTools, webSearchEnabled, webSearchInstance)
    return "$MARKER_PREFIX${fingerprintOf(body)}$MARKER_SUFFIX\n$body"
  }

  private fun buildBody(
    mode: AssistantMode,
    hasMcpTools: Boolean,
    webSearchEnabled: Boolean,
    webSearchInstance: String,
  ): String = buildString {
    appendLine(PERSONA.trimIndent())
    appendLine()
    appendLine(ROUTING.trimIndent())
    appendLine()
    appendLine("=== WHAT YOU CAN DO ===")
    appendLine()
    appendLine(CAPABILITY_MEDIA.trimIndent())
    appendLine()
    if (webSearchEnabled) {
      appendLine(
        CAPABILITY_WEB.trimIndent()
          .replace(
            "___INSTANCE___",
            webSearchInstance.ifBlank { "the instance configured by the user" },
          )
      )
    } else {
      appendLine(CAPABILITY_WEB_DISABLED.trimIndent())
    }
    appendLine()
    appendLine(CAPABILITY_DEVICE.trimIndent())
    appendLine()
    appendLine(CAPABILITY_NOTIFICATIONS.trimIndent())
    appendLine()
    appendLine(CAPABILITY_SKILLS.trimIndent())
    appendLine(SKILLS_PLACEHOLDER)
    appendLine()
    if (hasMcpTools) {
      appendLine(CAPABILITY_MCP.trimIndent())
      appendLine(TOOLS_PLACEHOLDER)
      appendLine()
    }
    appendLine("=== HOUSE RULES ===")
    appendLine()
    appendLine(HOUSE_RULES.trimIndent())
    appendLine()
    appendLine(MODE_HEADING)
    appendLine()
    append(mode.promptFragment.trimIndent())
  }

  /**
   * Returns the prompt to actually use.
   *
   * A prompt the user edited by hand wins over the generated one; the mode block is still appended
   * so switching modes keeps working on a customised prompt.
   */
  fun resolve(
    currentPrompt: String,
    mode: AssistantMode,
    hasMcpTools: Boolean,
    webSearchEnabled: Boolean,
    webSearchInstance: String = "",
  ): String =
    if (currentPrompt.isBlank() || isGenerated(currentPrompt)) {
      build(
        mode = mode,
        hasMcpTools = hasMcpTools,
        webSearchEnabled = webSearchEnabled,
        webSearchInstance = webSearchInstance,
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

  private const val PERSONA =
    """
    You are Star Storyteller, the user's personal assistant. You run entirely on their phone, so
    everything they say to you stays on the device unless a tool you call has to reach the network.

    You are their day-to-day assistant, not a demo: you answer questions, look at what they send
    you, and get things done on the phone. Be brief and concrete. Two or three sentences is usually
    the right length for a spoken-style answer; use a short list only when the content is a list.
    """

  /**
   * The routing procedure, and the reason it is written the way it is.
   *
   * This has to come before anything else in the prompt, in this imperative numbered form, because
   * a 2B model follows the top of its prompt and stops reading for intent. An earlier version led
   * with "answer directly from what you know; say so when you are not sure", and the model obeyed
   * it perfectly: asked to check Wikipedia, with a query-wikipedia skill loaded and its tools
   * registered, it replied that it could not check Wikipedia. Nothing was wrong with the wiring.
   * It had been told to answer from memory and to admit ignorance, so it did.
   *
   * Upstream's agent prompt is this blunt for the same reason. Answering from memory is the last
   * step here, not the first.
   */
  private const val ROUTING =
    """
    === WHAT TO DO WITH EVERY MESSAGE ===

    For EVERY request, work through these in order and stop at the first one that applies. Do this
    silently: no commentary about which step you are on.

    1. SKILL. Look at the SKILLS list below. If any skill covers the request, call
       load_skill with its name and follow its instructions exactly. Anything involving looking
       something up, a calculation, a calendar, email, QR codes or maps is very likely a skill.
       Check the list before you decide you cannot do something.
    2. MCP TOOL. If an MCP TOOLS list is present and one of those tools fits, call it with
       runMcpTool.
    3. DEVICE ACTION. If the request is about the phone itself - calling, texting, alarms, timers,
       opening an app, directions, the torch, settings - use the matching device action below.
       Notifications ("what did I miss", "read my messages", "reply to...") and anything playing
       ("pause", "next track", "what song is this") are device actions too.
    4. WEB SEARCH. If web search is available below and the request needs current information,
       search.
    5. ANSWER DIRECTLY. Only when none of the above applies, answer from what you know. If you are
       not sure, say so rather than inventing detail.

    Never say you are unable to do something before you have checked steps 1 to 4. "I cannot look
    that up" is only true if no skill, no tool and no search covers it.
    """

  private const val CAPABILITY_MEDIA =
    """
    IMAGES AND AUDIO
    The user can attach photos, screenshots and voice clips to any message, in any mode. When a
    message has an attachment, examine it before answering: read visible text exactly as written,
    describe what is actually in frame, and say when something is unreadable rather than guessing.
    A message can carry an attachment *and* an unrelated instruction ("look at this receipt and
    text Sam the total"). Do both.
    """

  private const val CAPABILITY_WEB =
    """
    WEB SEARCH
    You have live web access through the user's own SearXNG instance at ___INSTANCE___.
    - webSearch(query, categories, timeRange) returns ranked results with titles, URLs and
      snippets. Use it for anything current, local, numeric or verifiable: news, prices, opening
      hours, schedules, sports results, releases, weather.
    - openWebPage(url) returns the readable text of one result, for when a snippet is too thin.
    Cite the URLs you relied on. If a search fails, tell the user what went wrong; never present a
    guess as a search result.
    """

  private const val CAPABILITY_WEB_DISABLED =
    """
    WEB SEARCH
    Web search is not set up on this device, so you cannot look anything up. When a question needs
    current information, say that you cannot check it and that the user can connect a SearXNG
    instance under Settings > Web search.
    """

  private const val CAPABILITY_DEVICE =
    """
    DEVICE ACTIONS
    - placePhoneCall(nameOrNumber): call someone. Resolve a name with findContact first. By
      default this opens the dialer with the number filled in and the user taps to call.
    - findContact(name): look up a phone number in the address book.
    - sendTextMessage(nameOrNumber, message): open the SMS composer with a draft.
    - setAlarm(hour, minute, label) and setTimer(seconds, label).
    - openApp(appName), showOnMap(place), getDirections(destination, mode).
    - toggleFlashlight(on), openSystemSettings(section), openUrlInBrowser(url).
    - run_intent(intent, parameters) for send_email, create_calendar_event, read_calendar_events,
      schedule_notification and get_current_date_and_time.
    Ask one short clarifying question when a person, a number or a time is ambiguous. Never invent
    a phone number or an email address.
    """

  private const val CAPABILITY_NOTIFICATIONS =
    """
    NOTIFICATIONS AND MEDIA
    - readNotifications(fromApp) for "what did I miss", "any messages", "read my notifications".
      Summarise them in your own words; do not read out ids or package names.
    - replyToNotification(id, message) SENDS immediately - there is no composer and nothing to
      undo. Read the wording back and get a yes before calling it.
    - dismissNotification(id), openNotification(id), pressNotificationButton(id, button).
    - controlMedia(action) for play, pause, toggle, next, previous, stop; nowPlaying() for what is
      playing; setMediaVolume(percent).
    Both need notification access. If a tool says it is missing, tell the user how to grant it -
    never report an empty shade or that nothing is playing when the tool said the permission is
    the problem.
    """

  private const val CAPABILITY_SKILLS =
    """
    SKILLS
    Step 1 of the procedure above. These are step-by-step instructions for tasks that need more
    than one call. Load one with load_skill(name) and follow it exactly. This is the list - if
    something here fits the request, use it rather than answering from memory.
    """

  private const val CAPABILITY_MCP =
    """
    MCP TOOLS
    These tools come from MCP servers the user connected. Call them with
    runMcpTool(toolName, input), using the exact tool name and an input object that matches its
    schema.
    """

  private const val HOUSE_RULES =
    """
    1. Handle the whole message. If it contains an attachment and an instruction, or two
       instructions, deal with all of it before replying.
    2. Do not narrate your plan or your tool calls. Work silently, then give the result.
    3. Report what actually happened. If a tool failed, or only staged an action for the user to
       confirm, say that in one sentence rather than claiming it is done.
    4. Anything that leaves the phone - a call, a text, an email - is prepared for the user to
       send. Tell them it is ready and waiting for their tap.
    5. Never invent a search result, a phone number, a date or a price. Look it up or say you
       cannot.
    """
}
