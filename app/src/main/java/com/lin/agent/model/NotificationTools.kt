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

import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.notifications.NotificationRelay
import com.lin.agent.notifications.StagedReply

/** Reads out who has messaged, so the model can answer "who wants me?" without guessing. */
class UnreadTool(private val relay: NotificationRelay) : AgentTool {

  override val name = "unread_messages"

  override val description =
    "List the recent messages on this phone that can be replied to, and who they are from."

  override val parameters = emptyList<ToolParameter>()

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val waiting = relay.repliable.value
    if (waiting.isEmpty()) {
      return ToolResult.Ok(
        "Nothing is waiting. If the user expected something, notification access may be off - " +
          "they can turn it on in settings."
      )
    }
    return ToolResult.Ok(
      buildString {
        append("Messages that can be replied to:")
        waiting.forEach { append("\n- ").append(it.from).append(" (").append(it.app).append("): ").append(it.text) }
      }
    )
  }
}

/**
 * Writes a reply to a message, and stops.
 *
 * The staged-action rule, in the place it matters most. A notification reply has no composer
 * and no undo - the action fires and the words are gone - so this writes the reply onto the
 * screen and the person sends it. The model cannot reach [NotificationRelay.send] at all;
 * only the Send button can.
 *
 * Worth being exact about why the confirmation is the screen's rather than the model's. The
 * obvious design is for Lin to read the wording back and send when the user agrees, and that
 * puts the model in charge of deciding what agreement sounds like - on an action that cannot
 * be taken back, with a 2B model that has already been observed saying it did things it had
 * not. The button is ground truth; "yes" is an interpretation.
 */
class ReplyTool(private val relay: NotificationRelay) : AgentTool {

  override val name = "reply_to_message"

  override val description =
    "Write a reply to a message somebody sent. It appears on screen for the user to send - it " +
      "does not go on its own, so tell them what you have written rather than saying it is sent."

  override val parameters =
    listOf(
      ToolParameter(
        name = "to",
        type = ParamType.STRING,
        description = "Who the message was from, as they appear in the notification.",
      ),
      ToolParameter(
        name = "text",
        type = ParamType.STRING,
        description = "Exactly what the reply should say.",
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
      "reply_to_message writes a reply onto the screen for the user to send; it does not " +
      "send, so read the wording back and never say a message has gone."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val to = (arguments["to"] as? String)?.trim().orEmpty()
    val text = (arguments["text"] as? String)?.trim().orEmpty()
    if (to.isEmpty()) return ToolResult.Failed("No one was named to reply to.")
    if (text.isEmpty()) {
      return ToolResult.Failed("There was no reply to write. Ask the user what to say.")
    }

    val found =
      relay.findFrom(to)
        ?: return ToolResult.Failed(
          "There is no message from '$to' that can be replied to. It may have been dismissed, " +
            "or notification access may be off - the user can turn it on in settings."
        )

    relay.stage(StagedReply(to = found.from, app = found.app, text = text, key = found.key))

    return ToolResult.Ok(
      "The reply to ${found.from} is written and on screen, not sent - the user presses send. " +
        "Read the wording back to them so they know exactly what is about to go."
    )
  }
}
