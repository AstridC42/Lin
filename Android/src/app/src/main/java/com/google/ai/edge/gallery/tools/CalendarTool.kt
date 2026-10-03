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

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.calendar.CalendarTime
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGCalendarTool"

/**
 * The calendar, read and written directly.
 *
 * There were already skills for this, and they went through the calendar app: an intent that opens
 * its editor with the fields filled in. That covers making an appointment and nothing else. There
 * is no intent for moving one, none for cancelling one, and none for answering "what have I got on
 * Thursday" - so the assistant could put something in the diary and then knew nothing about it,
 * which is most of what a diary is for.
 *
 * These write to the calendar provider instead. That is a real edit to someone's real calendar,
 * which is why the descriptions say to read the change back before making it, and why cancelling
 * takes an id that can only have come from a search rather than a name that might match two things.
 */
class CalendarTool(
  private val context: Context,
  /** Overridable so tests do not depend on the wall clock. */
  private val now: () -> Long = { System.currentTimeMillis() },
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Look at the calendar. Use for \"what's on today\", \"am I free Thursday\", and before " +
        "changing or cancelling anything."
  )
  fun findCalendarEvents(
    @ToolParam(description = "How many days ahead to look. 1 is the rest of today.") days: Int = 1,
    @ToolParam(description = "Optional word to match in the title. Empty for everything.")
    matching: String = "",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val denied = permissionProblem(write = false)
      if (denied != null) return@runBlocking denied

      val start = now()
      val end = start + days.coerceIn(1, MAX_DAYS) * DAY_MILLIS
      val events = query(start, end, matching.trim())

      if (events.isEmpty()) {
        return@runBlocking mapOf(
          "status" to "succeeded",
          "events" to listOf<Map<String, Any>>(),
          "hint" to
            if (matching.isBlank()) "There is nothing in the calendar for that period."
            else "Nothing in the calendar matches \"$matching\" in that period.",
        )
      }

      sendProgress(
        "Read the calendar",
        inProgress = false,
        itemTitle = "Calendar",
        itemDescription = events.joinToString("\n") { "- ${it["when"]}: ${it["title"]}" },
      )
      mapOf(
        "status" to "succeeded",
        "events" to events,
        "hint" to "Use the id with saveCalendarEvent or deleteCalendarEvent.",
      )
    }
  }

  @Tool(
    description =
      "Put something in the calendar, or change something already in it. Read the details back " +
        "to the user before calling this."
  )
  fun saveCalendarEvent(
    @ToolParam(description = "What the appointment is called.") title: String,
    @ToolParam(
      description =
        "When it starts: '2026-09-04 14:30', '14:30' for the next time that comes round, " +
          "'tomorrow 09:00', or 'in 2 hours'."
    )
    startsAt: String,
    @ToolParam(description = "How long it lasts, in minutes.") minutes: Int = 60,
    @ToolParam(description = "Optional place.") location: String = "",
    @ToolParam(
      description = "The id of an existing event to change, from findCalendarEvents. Empty to add."
    )
    id: String = "",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val denied = permissionProblem(write = true)
      if (denied != null) return@runBlocking denied
      if (title.isBlank()) return@runBlocking failure("The appointment had no title.")

      val start =
        CalendarTime.parse(startsAt, now())
          ?: return@runBlocking failure(
            "\"$startsAt\" is not a time I can place. Use a date and time like " +
              "'2026-09-04 14:30', a clock time like '14:30', or 'tomorrow 09:00'."
          )
      val length = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

      val values =
        ContentValues().apply {
          put(CalendarContract.Events.TITLE, title.trim())
          put(CalendarContract.Events.DTSTART, start)
          put(CalendarContract.Events.DTEND, start + length * 60_000L)
          put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
          if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location.trim())
        }

      try {
        if (id.isBlank()) {
          val calendarId =
            writableCalendarId()
              ?: return@runBlocking failure(
                "This phone has no calendar that can be written to. Ask the user to add an " +
                  "account in the calendar app first."
              )
          values.put(CalendarContract.Events.CALENDAR_ID, calendarId)
          val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@runBlocking failure("The calendar refused the new appointment.")
          sendProgress("Added to the calendar", inProgress = false)
          mapOf(
            "status" to "succeeded",
            "id" to (uri.lastPathSegment ?: ""),
            "title" to title.trim(),
            "when" to WHEN_FORMAT.format(Date(start)),
            "hint" to "Confirm the day and time back to the user.",
          )
        } else {
          val eventId =
            id.toLongOrNull()
              ?: return@runBlocking failure("\"$id\" is not an event id. Call findCalendarEvents.")
          val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
          val changed = context.contentResolver.update(uri, values, null, null)
          if (changed == 0) {
            return@runBlocking failure(
              "There is no event with that id any more. Call findCalendarEvents again."
            )
          }
          sendProgress("Calendar updated", inProgress = false)
          mapOf(
            "status" to "succeeded",
            "id" to id,
            "title" to title.trim(),
            "when" to WHEN_FORMAT.format(Date(start)),
            "hint" to "Confirm the change back to the user.",
          )
        }
      } catch (e: SecurityException) {
        failure(PERMISSION_MESSAGE)
      } catch (e: Exception) {
        Log.w(TAG, "Could not save the event", e)
        failure("The calendar could not be written to.")
      }
    }
  }

  @Tool(
    description =
      "Cancel something in the calendar. Takes an id from findCalendarEvents, never a name, and " +
        "the user must have agreed to it first."
  )
  fun deleteCalendarEvent(
    @ToolParam(description = "The event id from findCalendarEvents.") id: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val denied = permissionProblem(write = true)
      if (denied != null) return@runBlocking denied
      val eventId =
        id.toLongOrNull()
          ?: return@runBlocking failure(
            "\"$id\" is not an event id. Call findCalendarEvents and use the id from there."
          )
      try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val removed = context.contentResolver.delete(uri, null, null)
        if (removed == 0) {
          return@runBlocking failure("There is no event with that id. It may already be gone.")
        }
        sendProgress("Removed from the calendar", inProgress = false)
        mapOf("status" to "succeeded", "id" to id)
      } catch (e: SecurityException) {
        failure(PERMISSION_MESSAGE)
      } catch (e: Exception) {
        Log.w(TAG, "Could not delete the event", e)
        failure("The calendar could not be written to.")
      }
    }
  }

  /** The events between [start] and [end], newest first, capped so the model is not flooded. */
  private fun query(start: Long, end: Long, matching: String): List<Map<String, Any>> {
    val uri =
      CalendarContract.Instances.CONTENT_URI
        .buildUpon()
        .appendPath(start.toString())
        .appendPath(end.toString())
        .build()
    val projection =
      arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
      )
    val results = mutableListOf<Map<String, Any>>()
    try {
      context.contentResolver
        .query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
        ?.use { cursor ->
          while (cursor.moveToNext() && results.size < MAX_EVENTS) {
            val title = cursor.getString(1).orEmpty().ifBlank { "(no title)" }
            if (matching.isNotEmpty() && !title.contains(matching, ignoreCase = true)) continue
            val begin = cursor.getLong(2)
            results.add(
              buildMap {
                put("id", cursor.getLong(0).toString())
                put("title", title)
                put("when", WHEN_FORMAT.format(Date(begin)))
                put("all_day", cursor.getInt(5) == 1)
                val location = cursor.getString(4).orEmpty()
                if (location.isNotBlank()) put("location", location)
              }
            )
          }
        }
    } catch (e: SecurityException) {
      Log.w(TAG, "Calendar read refused", e)
    } catch (e: Exception) {
      Log.w(TAG, "Could not read the calendar", e)
    }
    return results
  }

  /**
   * A calendar that can be written to, preferring the one the phone treats as primary.
   *
   * A phone often has several - a work account, a holidays feed, a birthdays feed - and most of
   * them are read-only. Writing to the wrong one puts the appointment somewhere the user does not
   * look, which reads as the assistant having done nothing.
   */
  private fun writableCalendarId(): Long? {
    val projection =
      arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
      )
    return try {
      context.contentResolver
        .query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
        ?.use { cursor ->
          var fallback: Long? = null
          while (cursor.moveToNext()) {
            val accessLevel = cursor.getInt(2)
            if (accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
            val id = cursor.getLong(0)
            if (cursor.getInt(1) == 1) return@use id
            if (fallback == null) fallback = id
          }
          fallback
        }
    } catch (e: Exception) {
      Log.w(TAG, "Could not find a writable calendar", e)
      null
    }
  }

  /**
   * Why the calendar cannot be reached, or null when it can.
   *
   * Kept apart from an empty result on purpose. "You have nothing on Thursday" and "I have not been
   * allowed to look" are different answers, and only one of them is something the user can act on.
   */
  private fun permissionProblem(write: Boolean): Map<String, Any>? {
    val needed =
      if (write) listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
      else listOf(Manifest.permission.READ_CALENDAR)
    val missing =
      needed.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
      }
    return if (missing.isEmpty()) null else failure(PERMISSION_MESSAGE)
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

  private companion object {
    const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /** Far enough for "what have I got next month", not so far that a year comes back at once. */
    const val MAX_DAYS = 60

    /** Enough for a full diary day without burying the model. */
    const val MAX_EVENTS = 20

    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 24 * 60

    val WHEN_FORMAT = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    const val PERMISSION_MESSAGE =
      "Lin has not been allowed to use the calendar. Tell the user to allow it from " +
        "the Guide panel in the app. Do not claim the calendar is empty."
  }
}
