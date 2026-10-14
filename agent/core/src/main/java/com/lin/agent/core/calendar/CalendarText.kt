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

package com.lin.agent.core.calendar

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One thing in the calendar, as this app needs it. */
data class CalEvent(
  val title: String,
  val startMillis: Long,
  val endMillis: Long,
  /**
   * Whether this is an all-day event, which changes how its time must be read.
   *
   * Not a display detail. See [dayOf] - an all-day event's start is stored at midnight UTC
   * rather than midnight where the person is, so reading it in the local zone puts it on the
   * wrong day for everybody west of Greenwich.
   */
  val allDay: Boolean = false,
  val location: String = "",
)

/** How many days ahead "what is coming up" looks, unless asked otherwise. */
const val DEFAULT_DAYS_AHEAD = 7

/** The most events worth putting in front of a small model at once. */
private const val MAX_EVENTS = 40

/**
 * The day an event falls on, as `yyyy-MM-dd`.
 *
 * The whole reason this function exists is the all-day case. Android stores an all-day event's
 * start at midnight **UTC**, not midnight where the person is - so formatting it in the local
 * zone gives 03:00 on the right day in Jerusalem and 19:00 on the *previous* day in New York.
 * An assistant that says somebody's birthday was yesterday is worse than one with no calendar
 * at all, so an all-day event is read in UTC and a timed one in the local zone.
 */
internal fun dayOf(event: CalEvent, zone: TimeZone): String {
  val calendar = Calendar.getInstance(if (event.allDay) TimeZone.getTimeZone("UTC") else zone)
  calendar.timeInMillis = event.startMillis
  return "%04d-%02d-%02d".format(
    calendar.get(Calendar.YEAR),
    calendar.get(Calendar.MONTH) + 1,
    calendar.get(Calendar.DAY_OF_MONTH),
  )
}

/** `yyyy-MM-dd` for a moment in [zone]. */
internal fun dayAt(millis: Long, zone: TimeZone): String {
  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = millis
  return "%04d-%02d-%02d".format(
    calendar.get(Calendar.YEAR),
    calendar.get(Calendar.MONTH) + 1,
    calendar.get(Calendar.DAY_OF_MONTH),
  )
}

/** `HH:mm` in [zone]. */
private fun clockAt(millis: Long, zone: TimeZone): String {
  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = millis
  return "%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
}

/** "Tuesday 14 October", for a day heading. */
internal fun dayName(day: String, zone: TimeZone, now: Long): String {
  val parts = day.split("-").mapNotNull { it.toIntOrNull() }
  if (parts.size != 3) return day
  val calendar = Calendar.getInstance(zone)
  calendar.set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)

  val label =
    when (day) {
      dayAt(now, zone) -> "Today"
      dayAt(now + DAY_MS, zone) -> "Tomorrow"
      else -> null
    }
  val written =
    "%s %d %s".format(
      calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
        ?: day,
      parts[2],
      calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()) ?: "",
    )
  return if (label == null) written else "$label, $written"
}

/**
 * The events, written out for a model to read.
 *
 * Grouped by day with a heading, because a flat list of timestamps is something a 2B model
 * reliably gets wrong when asked "what about Thursday". Capped, because a calendar with two
 * hundred entries in it would otherwise be the whole context window.
 */
fun describeEvents(events: List<CalEvent>, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
  if (events.isEmpty()) return "Nothing in the calendar for that time."

  val shown = events.sortedBy { it.startMillis }.take(MAX_EVENTS)
  val byDay = shown.groupBy { dayOf(it, zone) }

  return buildString {
    byDay.toSortedMap().forEach { (day, onThatDay) ->
      if (isNotEmpty()) append("\n")
      append(dayName(day, zone, now)).append(":")
      onThatDay.forEach { event ->
        append("\n- ")
        if (event.allDay) {
          append("All day: ")
        } else {
          append(clockAt(event.startMillis, zone))
          // Only when it ends at a different minute. "14:00-14:00" is noise, and a zero-length
          // event is how a reminder often arrives.
          if (event.endMillis > event.startMillis) {
            append("-").append(clockAt(event.endMillis, zone))
          }
          append(" ")
        }
        append(event.title.ifBlank { "(no title)" })
        if (event.location.isNotBlank()) append(" (").append(event.location.trim()).append(")")
      }
    }
    if (events.size > shown.size) {
      append("\n\n")
      append(events.size - shown.size)
      append(" more are not shown.")
    }
  }
}

/**
 * Midnight at the start of the day [now] falls in, where the person is.
 *
 * Not `now - now % DAY_MS`, which is the obvious line and is midnight **UTC**. Three hours out
 * in Jerusalem and on the wrong day entirely for anywhere west of Greenwich between midnight
 * and dawn - so "what is on today" would quietly answer for yesterday. The same mistake as
 * reading an all-day event in the local zone, from the other direction.
 */
fun startOfToday(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = now
  calendar.set(Calendar.HOUR_OF_DAY, 0)
  calendar.set(Calendar.MINUTE, 0)
  calendar.set(Calendar.SECOND, 0)
  calendar.set(Calendar.MILLISECOND, 0)
  return calendar.timeInMillis
}

/**
 * The last moment of the day [days] - 1 days after the one [now] falls in.
 *
 * Counted in days on a calendar rather than in milliseconds, because a day is not always
 * twenty-four hours. Adding `days * DAY_MS` across a clock change lands an hour short, which
 * drops whatever is last on the final evening - the one event somebody would notice missing.
 */
fun endOfDaysAhead(now: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long {
  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = startOfToday(now, zone)
  calendar.add(Calendar.DAY_OF_MONTH, days.coerceAtLeast(1))
  return calendar.timeInMillis - 1
}

internal const val DAY_MS = 24 * 60 * 60 * 1000L
