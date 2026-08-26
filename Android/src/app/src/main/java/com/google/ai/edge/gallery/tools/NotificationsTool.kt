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

package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.notifications.NotificationSummary
import com.google.ai.edge.gallery.notifications.StarNotificationListener
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Reading and acting on the notification shade.
 *
 * "What did I miss", "read my messages", "reply to Sam", "clear that" - the everyday assistant
 * requests that no other API can answer.
 *
 * Replying is the one thing here that reaches another person, and unlike a call or a text it cannot
 * be staged: firing a notification's reply action sends it, with no composer in between. So it is
 * the tool most worth being careful with, and its description says to confirm the wording first.
 * The alternative - never offering it - would leave "reply to Sam" needing the phone in hand, which
 * is the whole point of asking.
 */
class NotificationsTool(private val context: Context) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Read the phone's notifications. Use for \"what did I miss\", \"any messages\", and before replying to or dismissing one."
  )
  fun readNotifications(
    @ToolParam(
      description =
        "Optional app name to filter by, for example 'WhatsApp'. Leave empty for everything."
    )
    fromApp: String = ""
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val unavailable = unavailableReason()
      if (unavailable != null) return@runBlocking unavailable

      sendProgress("Reading notifications", inProgress = true)
      val all = StarNotificationListener.activeNotifications(context)
      val filtered =
        if (fromApp.isBlank()) all
        else all.filter { it.appName.contains(fromApp.trim(), ignoreCase = true) }

      if (filtered.isEmpty()) {
        sendProgress("No notifications", inProgress = false)
        return@runBlocking mapOf(
          "status" to "succeeded",
          "notifications" to listOf<Map<String, Any>>(),
          "hint" to
            if (fromApp.isBlank()) "There are no notifications."
            else "There are no notifications from \"$fromApp\".",
        )
      }

      sendProgress(
        "Read ${filtered.size} notification(s)",
        inProgress = false,
        itemTitle = "Notifications",
        itemDescription = filtered.joinToString("\n") { "- ${it.appName}: ${it.title}" },
      )
      mapOf(
        "status" to "succeeded",
        "notifications" to filtered.take(MAX_NOTIFICATIONS).map { it.toMap() },
        "hint" to
          "Summarise these for the user in their own words. Use the id field with " +
            "replyToNotification, dismissNotification or openNotification.",
      )
    }
  }

  @Tool(
    description =
      "Reply to a message notification. SENDS immediately with no composer, so read the wording back and get agreement first."
  )
  fun replyToNotification(
    @ToolParam(description = "The notification id from readNotifications.") id: String,
    @ToolParam(description = "The message to send.") message: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val unavailable = unavailableReason()
      if (unavailable != null) return@runBlocking unavailable
      if (id.isBlank()) return@runBlocking failure("No notification id was given.")
      if (message.isBlank()) {
        return@runBlocking failure("The reply was empty, so nothing was sent.")
      }

      if (!StarNotificationListener.reply(id, message)) {
        return@runBlocking failure(
          "That notification could not be replied to. It may have been dismissed, or the app may " +
            "not offer a reply action. Ask the user whether to open the app instead."
        )
      }
      sendProgress("Reply sent", inProgress = false)
      mapOf("status" to "succeeded", "sent" to message, "hint" to "The reply was sent.")
    }
  }

  @Tool(description = "Dismiss a notification, as swiping it away would.")
  fun dismissNotification(
    @ToolParam(description = "The notification id from readNotifications.") id: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val unavailable = unavailableReason()
      if (unavailable != null) return@runBlocking unavailable
      if (id.isBlank()) return@runBlocking failure("No notification id was given.")
      if (!StarNotificationListener.dismiss(id)) {
        return@runBlocking failure("That notification could not be dismissed.")
      }
      sendProgress("Dismissed", inProgress = false)
      mapOf("status" to "succeeded")
    }
  }

  @Tool(description = "Open the app a notification came from, as tapping the notification would.")
  fun openNotification(
    @ToolParam(description = "The notification id from readNotifications.") id: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val unavailable = unavailableReason()
      if (unavailable != null) return@runBlocking unavailable
      if (id.isBlank()) return@runBlocking failure("No notification id was given.")
      if (!StarNotificationListener.open(id)) {
        return@runBlocking failure("That notification could not be opened.")
      }
      mapOf("status" to "succeeded")
    }
  }

  @Tool(
    description =
      "Press a notification button by its label, e.g. Snooze."
  )
  fun pressNotificationButton(
    @ToolParam(description = "The notification id from readNotifications.") id: String,
    @ToolParam(description = "The exact button label, as listed in the notification's actions.")
    button: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val unavailable = unavailableReason()
      if (unavailable != null) return@runBlocking unavailable
      if (id.isBlank() || button.isBlank()) {
        return@runBlocking failure("Both a notification id and a button label are needed.")
      }
      if (!StarNotificationListener.runAction(id, button)) {
        return@runBlocking failure(
          "That notification has no button called \"$button\". Check the actions listed for it."
        )
      }
      mapOf("status" to "succeeded")
    }
  }

  /**
   * Why notifications cannot be read right now, or null when they can.
   *
   * The two failures need different answers and must not be collapsed: not granted is something the
   * user can fix and should be told how to, whereas granted-but-not-yet-connected is a wait. Both
   * would otherwise look like an empty shade, and the model would cheerfully report that there is
   * nothing to read.
   */
  private fun unavailableReason(): Map<String, Any>? =
    when {
      !StarNotificationListener.isGranted(context) ->
        failure(
          "Notification access has not been granted. Tell the user to turn on Star Storyteller " +
            "under Settings > Notifications > Device & app notifications, or from the Guide panel " +
            "in the app. Do not claim there are no notifications."
        )
      !StarNotificationListener.isConnected() ->
        failure(
          "Notification access is granted but not connected yet. Ask the user to try again in a " +
            "moment. Do not claim there are no notifications."
        )
      else -> null
    }

  private suspend fun sendProgress(
    label: String,
    inProgress: Boolean,
    itemTitle: String = "",
    itemDescription: String = "",
  ) {
    executionContext
      ?.actionChannel
      ?.send(
        SkillProgressToolAction(
          label = label,
          inProgress = inProgress,
          addItemTitle = itemTitle,
          addItemDescription = itemDescription,
        )
      )
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  companion object {
    /** Enough to answer "what did I miss" without burying the model in a hundred rows. */
    private const val MAX_NOTIFICATIONS = 8

    private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * How much of one notification's body is worth handing to the model.
     *
     * An expanded message, an email or a news notification can run to hundreds of words. Eight of
     * those at full length is more than the whole token budget, and the answer the user wants -
     * who it is from and roughly what it says - needs only the opening.
     */
    private const val MAX_TEXT_CHARS = 240

    internal fun NotificationSummary.toMap(): Map<String, Any> =
      mapOf(
        "id" to key,
        "app" to appName,
        "title" to title.take(MAX_TEXT_CHARS),
        "text" to text.take(MAX_TEXT_CHARS),
        "time" to TIME_FORMAT.format(Date(postedAtMillis)),
        "can_reply" to canReply,
        "can_dismiss" to isClearable,
        "buttons" to actions,
      )
  }
}
