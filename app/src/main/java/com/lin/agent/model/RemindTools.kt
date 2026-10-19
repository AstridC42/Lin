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

import com.lin.agent.core.remind.reminderMatching
import com.lin.agent.core.remind.whenInWords
import com.lin.agent.core.remind.whenItFires
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.remind.ReminderBook
import java.time.ZoneId

/**
 * Tells the user something at a time they chose.
 *
 * The thing Lin was asked for and did not have. Until this existed the nearest tool opened the
 * clock app with an alarm half filled in, which is not a reminder - it is Lin asking somebody
 * else to set one - and the gap showed: "remind me to take the bins out at six" got an answer
 * about the clock app being open.
 *
 * It acts rather than staging, unlike sending a message or placing a call, and that is not an
 * inconsistency. A reminder set wrongly costs one notification and can be cancelled by asking;
 * a message sent wrongly cannot be taken back. The staging rule is about actions that are
 * irreversible, not about all actions.
 */
class RemindTool(private val book: ReminderBook, private val zone: () -> ZoneId) : AgentTool {

  override val name = "remind_me"

  override val description =
    "Send the user a notification at a time they choose. Use this for anything they want to " +
      "be told later - a task, an errand, a time to leave."

  override val parameters =
    listOf(
      ToolParameter(
        name = "when",
        type = ParamType.STRING,
        description =
          "When to tell them, in their words: 'in 25 minutes', '18:30', 'tomorrow morning', " +
            "'monday at 9am', '2026-10-17 07:00'. A time of day with no day means the next one.",
      ),
      ToolParameter(
        name = "text",
        type = ParamType.STRING,
        description = "What to say at that moment, in the user's own words where possible.",
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
    "remind_me is how the user is told something later: Lin's own notification arrives at " +
      "that moment whether or not the app is open. Prefer it to set_timer_or_alarm for " +
      "anything that is a reminder rather than a clock alarm, and always read back the time " +
      "it gives you."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val said = (arguments["when"] as? String)?.trim().orEmpty()
    val text = (arguments["text"] as? String)?.trim().orEmpty()
    if (text.isEmpty()) return ToolResult.Failed("There is nothing to be reminded of. Ask what.")
    if (said.isEmpty()) return ToolResult.Failed(WHEN_UNKNOWN)

    val now = System.currentTimeMillis()
    val here = zone()
    val at =
      whenItFires(said, now, here)
        ?: return ToolResult.Failed("'$said' is not a time this can use. $WHEN_UNKNOWN")

    if (at <= now) {
      return ToolResult.Failed(
        "${whenInWords(at, now, here)} has already passed. Ask the user for a time ahead, or " +
          "set it for the same time tomorrow if that is obviously what they meant."
      )
    }

    val reminder = book.set(text, at)
    val late =
      if (book.exactAllowed()) ""
      else
        " It may arrive a few minutes late: exact alarms are switched off for Lin, which the " +
          "user can change under Alarms & reminders in Android settings."
    return ToolResult.Ok(
      "Set: \"${reminder.text}\", ${whenInWords(reminder.atMillis, now, here)}. Lin will send " +
        "the notification itself.$late Read the time back so the user can correct it."
    )
  }

  private companion object {
    const val WHEN_UNKNOWN =
      "Ask the user when, and give it as a clear time: 'in 20 minutes', '18:30', 'tomorrow " +
        "at 8'. Do not guess a time."
  }
}

/** What is already set, so "what have you got for me?" does not need the settings screen. */
class ListRemindersTool(private val book: ReminderBook, private val zone: () -> ZoneId) : AgentTool {

  override val name = "list_reminders"

  override val description = "List the reminders Lin will send, soonest first."

  override val parameters = emptyList<ToolParameter>()

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val now = System.currentTimeMillis()
    val ahead = book.ahead(now)
    if (ahead.isEmpty()) return ToolResult.Ok("No reminders are set.")
    return ToolResult.Ok(
      buildString {
        append("Reminders set:")
        ahead.forEach {
          append("\n- ").append(it.text).append(" - ").append(whenInWords(it.atMillis, now, zone()))
        }
      }
    )
  }
}

/**
 * Cancels one reminder.
 *
 * Only ever one, and only when it is unambiguous. Cancelling the wrong reminder is a silent
 * failure - nothing happens at the moment something should have - so two possible matches come
 * back as a question rather than a guess.
 */
class CancelReminderTool(private val book: ReminderBook, private val zone: () -> ZoneId) :
  AgentTool {

  override val name = "cancel_reminder"

  override val description = "Cancel a reminder the user no longer wants."

  override val parameters =
    listOf(
      ToolParameter(
        name = "which",
        type = ParamType.STRING,
        description = "What the reminder was about, in enough words to tell it from the others.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val which = (arguments["which"] as? String)?.trim().orEmpty()
    if (which.isEmpty()) return ToolResult.Failed("Ask the user which reminder to cancel.")

    val now = System.currentTimeMillis()
    val ahead = book.ahead(now)
    if (ahead.isEmpty()) return ToolResult.Failed("There are no reminders set to cancel.")

    val found =
      reminderMatching(ahead, which)
        ?: return ToolResult.Failed(
          "'$which' matches no single reminder. Read these out and ask which one: " +
            ahead.joinToString("; ") {
              "${it.text} (${whenInWords(it.atMillis, now, zone())})"
            }
        )

    return if (book.cancel(found.id)) {
      ToolResult.Ok("Cancelled: \"${found.text}\" will not be sent.")
    } else {
      ToolResult.Failed("\"${found.text}\" was already gone. Nothing was cancelled.")
    }
  }
}
