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

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.ai.edge.gallery.R

/**
 * The modes of the single assistant chat screen.
 *
 * Upstream Edge Gallery exposes one screen per capability: a text chat, an "ask image" screen, an
 * "ask audio" screen, mobile actions, and the skills/MCP agent. Star Storyteller merges all of
 * them into one conversation, and a mode is the *bias* applied to that conversation rather than a
 * separate screen.
 *
 * That distinction matters for the way the app is meant to be used: every mode keeps the image
 * picker, the audio recorder and the full tool set available, so a single turn can drop in a photo
 * *and* ask for a phone call. Switching mode only rewrites the system prompt, so the model reaches
 * for the right capability first.
 */
enum class AssistantMode(
  /** Stable id persisted in the user's settings. */
  val id: String,
  @StringRes val labelRes: Int,
  @StringRes val descriptionRes: Int,
  val icon: ImageVector,
  /** The mode-specific block appended to the assistant system prompt. */
  val promptFragment: String,
) {
  AUTO(
    id = "auto",
    labelRes = R.string.assistant_mode_auto,
    descriptionRes = R.string.assistant_mode_auto_desc,
    icon = Icons.Rounded.AutoAwesome,
    promptFragment =
      """
      Current mode: AUTO.
      Decide per message which capability fits, and combine them freely within one turn:
      - Answer directly when you already know the answer.
      - Look at any attached image or listen to any attached audio before answering about it.
      - Use webSearch when the answer depends on anything current or verifiable.
      - Use the device tools for calls, texts, alarms, timers, navigation and apps.
      - Use load_skill or runMcpTool for multi-step work that a skill or MCP server covers.
      A single message may need several of these. Handle all parts of it before replying.
      """,
  ),
  CHAT(
    id = "chat",
    labelRes = R.string.assistant_mode_chat,
    descriptionRes = R.string.assistant_mode_chat_desc,
    icon = Icons.Rounded.Forum,
    promptFragment =
      """
      Current mode: CHAT.
      Prefer answering from your own knowledge in a natural, conversational way. Do not reach for
      tools for something you can answer directly. Still use a tool when the user explicitly asks
      for an action, attaches media to look at, or asks about something you cannot know without
      checking, and say plainly when you are unsure rather than guessing.
      """,
  ),
  MEDIA(
    id = "media",
    labelRes = R.string.assistant_mode_media,
    descriptionRes = R.string.assistant_mode_media_desc,
    icon = Icons.Rounded.Image,
    promptFragment =
      """
      Current mode: MEDIA.
      Attachments are the subject of the conversation. Examine every attached image or audio clip
      carefully and ground your answer in what is actually there: read visible text verbatim,
      describe what you can see or hear, and say when something is unreadable or ambiguous instead
      of filling it in. After describing the attachment, carry out whatever the user asked you to
      do with it, including actions in other capabilities.
      """,
  ),
  RESEARCH(
    id = "research",
    labelRes = R.string.assistant_mode_research,
    descriptionRes = R.string.assistant_mode_research_desc,
    icon = Icons.Rounded.TravelExplore,
    promptFragment =
      """
      Current mode: WEB.
      Search before you answer. Call webSearch with a focused query, read the snippets, and call
      openWebPage when a result needs more detail. Base the answer on what you found, cite the
      URLs you used, and state it plainly when the search came back empty or contradictory rather
      than falling back on memory without saying so.
      """,
  ),
  ACTIONS(
    id = "actions",
    labelRes = R.string.assistant_mode_actions,
    descriptionRes = R.string.assistant_mode_actions_desc,
    icon = Icons.Rounded.PhoneIphone,
    promptFragment =
      """
      Current mode: DEVICE.
      The user wants something done on the phone. Map the request onto the device tools:
      placePhoneCall, sendTextMessage, setAlarm, setTimer, openApp, showOnMap, getDirections,
      toggleFlashlight, openSystemSettings, openUrlInBrowser, and run_intent for email and
      calendar. Resolve a person's name with findContact first. If a required detail is missing or
      several contacts match, ask one short question instead of guessing. Report exactly what
      happened, including when the action only staged something for the user to confirm.
      """,
  ),
  AGENT(
    id = "agent",
    labelRes = R.string.assistant_mode_agent,
    descriptionRes = R.string.assistant_mode_agent_desc,
    icon = Icons.Rounded.Bolt,
    promptFragment =
      """
      Current mode: AGENT.
      Treat the request as a task to complete, not a question to answer. Find the matching skill in
      the SKILLS list and load it with load_skill, or call runMcpTool for a matching MCP tool, then
      follow the instructions you get back exactly. Work silently and report only the final result.
      """,
  );

  companion object {
    val DEFAULT = AUTO

    fun fromId(id: String?): AssistantMode = entries.find { it.id == id } ?: DEFAULT
  }
}
