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

package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.Reminder
import com.google.ai.edge.gallery.reminders.ReminderScheduler
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Reminders the assistant keeps, lists and cancels.
 *
 * Distinct from `setAlarm`, which hands an alarm to the clock app and loses sight of it: there is
 * no API to read or cancel what another app owns, so "what reminders do I have" and "cancel that"
 * could not be answered. These are stored here and survive a reboot.
 */
class RemindersTool(
  private val context: Context,
  private val repository: DataStoreRepository,
  /** Overridable so tests do not depend on the wall clock. */
  private val now: () -> Long = { System.currentTimeMillis() },
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Set a reminder the assistant delivers. Prefer this over setAlarm for \"remind me to...\"."
  )
  fun setReminder(
    @ToolParam(description = "What to remind the user about, in their own words.") text: String,
    @ToolParam(
      description =
        "When, as either a number of minutes from now (for example '30') or a clock time " +
          "today or tomorrow in 24-hour form (for example '16:00')."
    )
    whenToFire: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (text.isBlank()) return@runBlocking failure("The reminder had no text.")
      val dueAt =
        parseWhen(whenToFire, now())
          ?: return@runBlocking failure(
            "\"$whenToFire\" is not a time I can read. Use minutes from now, like '30', or a " +
              "24-hour clock time, like '16:00'."
          )

      val reminder =
        Reminder.newBuilder()
          .setId(UUID.randomUUID().toString())
          .setText(text.trim())
          .setDueAtMillis(dueAt)
          .setDelivered(false)
          .build()

      val current = repository.readReminders()
      repository.saveReminders(current.toBuilder().addReminder(reminder).build())
      ReminderScheduler.schedule(context, reminder)

      sendProgress("Reminder set", inProgress = false)
      val exact = ReminderScheduler.canScheduleExact(context)
      mapOf(
        "status" to "succeeded",
        "id" to reminder.id,
        "text" to reminder.text,
        "due" to DISPLAY_FORMAT.format(Date(dueAt)),
        "hint" to
          if (exact) "Confirm the time back to the user."
          else
            "Exact alarms are not permitted for this app, so it may arrive a few minutes late. " +
              "Tell the user they can allow exact alarms in the app's system settings.",
      )
    }
  }

  @Tool(description = "List the reminders that have not been delivered yet.")
  fun listReminders(): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val pending =
        repository.readReminders().reminderList
          .filter { !it.delivered }
          .sortedBy { it.dueAtMillis }
      if (pending.isEmpty()) {
        return@runBlocking mapOf(
          "status" to "succeeded",
          "reminders" to listOf<Map<String, Any>>(),
          "hint" to "There are no reminders set.",
        )
      }
      mapOf(
        "status" to "succeeded",
        "reminders" to
          pending.map {
            mapOf(
              "id" to it.id,
              "text" to it.text,
              "due" to DISPLAY_FORMAT.format(Date(it.dueAtMillis)),
            )
          },
      )
    }
  }

  @Tool(description = "Cancel a reminder by its id, from listReminders.")
  fun cancelReminder(
    @ToolParam(description = "The reminder id.") id: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val current = repository.readReminders()
      val match = current.reminderList.firstOrNull { it.id == id }
        ?: return@runBlocking failure(
          "There is no reminder with that id. Call listReminders first."
        )

      val updated = current.toBuilder().clearReminder()
      for (reminder in current.reminderList) {
        if (reminder.id != id) updated.addReminder(reminder)
      }
      repository.saveReminders(updated.build())
      ReminderScheduler.cancel(context, id)

      sendProgress("Reminder cancelled", inProgress = false)
      mapOf("status" to "succeeded", "cancelled" to match.text)
    }
  }

  private suspend fun sendProgress(label: String, inProgress: Boolean) {
    executionContext
      ?.actionChannel
      ?.send(SkillProgressToolAction(label = label, inProgress = inProgress))
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  companion object {
    private val DISPLAY_FORMAT = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    private val MINUTES = Regex("^\\s*(\\d{1,4})\\s*(m|min|mins|minute|minutes)?\\s*$")
    private val HOURS = Regex("^\\s*(\\d{1,3})\\s*(h|hr|hrs|hour|hours)\\s*$")
    private val CLOCK = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$")

    /**
     * Turns what the model passed into an absolute time, or null when it cannot be read.
     *
     * A clock time that has already passed today means tomorrow. Asking at 5pm to be reminded at
     * "09:00" can only mean the morning; scheduling it in the past would fire it instantly, which
     * looks like the reminder feature being broken rather than the time being ambiguous.
     *
     * Returning null rather than guessing matters just as much: a reminder silently set for the
     * wrong time is worse than one the assistant admits it could not understand.
     */
    internal fun parseWhen(value: String, nowMillis: Long): Long? {
      MINUTES.matchEntire(value)?.let { match ->
        val minutes = match.groupValues[1].toIntOrNull() ?: return null
        if (minutes <= 0) return null
        return nowMillis + minutes * 60_000L
      }
      HOURS.matchEntire(value)?.let { match ->
        val hours = match.groupValues[1].toIntOrNull() ?: return null
        if (hours <= 0) return null
        return nowMillis + hours * 3_600_000L
      }
      CLOCK.matchEntire(value)?.let { match ->
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val calendar =
          Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
          }
        if (calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
      }
      return null
    }
  }
}
