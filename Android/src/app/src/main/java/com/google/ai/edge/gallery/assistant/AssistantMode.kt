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
 * "ask audio" screen, mobile actions, and the skills/MCP agent. Lin merges all of
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
      // Deliberately short, and deliberately without a list.
      //
      // This block used to restate the routing procedure at the top of the prompt, capability by
      // capability, and it is appended last - so the model read the whole ordering twice, the
      // second time in different words, and paid for it twice out of a budget that had already
      // run out on the first message of a new conversation. The routing says what to do; the
      // default mode's job is only to decline to narrow it.
      """
      Current mode: AUTO.
      Any capability may apply, and several may apply to one message - a skill, a phone action
      and a search in a single turn is normal. Check the SKILLS list first, follow the routing
      procedure above, and handle every part of the message before you reply.
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
      Talk. Answer from your own knowledge rather than reaching for a tool. Still use one when
      they ask for an action, attach something to look at, or ask about something you cannot know
      without checking. Say when you are unsure instead of guessing.
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
      The attachment is the subject. Ground every word in what is actually there: read visible
      text verbatim, say what you can see or hear, and say when something is unreadable rather
      than filling it in. Then do whatever they asked you to do with it.
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
      Search before you answer: webSearch with a focused query, then openWebPage when a result
      needs more. Answer from what you found and cite the URLs. If it came back empty or
      contradictory, say so rather than quietly falling back on memory.
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
      They want something done on the phone. Use the device tools: placePhoneCall,
      sendTextMessage, setAlarm, setTimer, openApp, showOnMap, getDirections, toggleFlashlight,
      openSystemSettings, openUrlInBrowser, run_intent for email and calendar. Resolve a name
      with findContact first. Missing detail or several matches: ask one short question. Report
      exactly what happened, staged included.
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
