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

/** A validated clock time. */
data class ClockTime(val hour: Int, val minute: Int)

/**
 * [hour]:[minute] if that is a time of day, or null.
 *
 * The model produces these, and a 2B model asked for an hour will occasionally produce 25, or
 * -1, or the minutes in the hour field. An alarm is set for later - usually hours later, often
 * overnight - so a wrong one is discovered by being woken by it, which is the worst moment to
 * find out. Refusing is cheap and the model can be told to ask.
 */
fun clockTime(hour: Int?, minute: Int?): ClockTime? {
  val h = hour ?: return null
  val m = minute ?: 0
  if (h !in 0..23 || m !in 0..59) return null
  return ClockTime(h, m)
}

/**
 * [minutes] as a timer length, or null if it is not a sensible one.
 *
 * Bounded at a day. Beyond that it is not a timer, it is an appointment, and a model that has
 * misread "ten" as "ten thousand" should not be able to set something that fires next week.
 */
fun timerMinutes(minutes: Int?): Int? {
  val value = minutes ?: return null
  return if (value in 1..MAX_TIMER_MINUTES) value else null
}

/**
 * The start of a dated event in [zone], or null if the date or time will not do.
 *
 * Taken apart rather than parsed from one string. A model handed a date format produces
 * "tomorrow", "14/10", "2026-10-14T15:00+03:00" and "next Tuesday" in roughly equal measure;
 * separate numeric fields are the one shape it cannot be creative with, and the one this can
 * check. The prompt gives it today's date so it can do the arithmetic.
 */
fun startOfEvent(
  year: Int?,
  month: Int?,
  day: Int?,
  hour: Int?,
  minute: Int?,
  zone: TimeZone = TimeZone.getDefault(),
): Long? {
  if (year == null || month == null || day == null) return null
  if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12 || day !in 1..31) return null
  val time = clockTime(hour ?: 0, minute ?: 0) ?: return null

  val calendar = Calendar.getInstance(zone, Locale.US)
  calendar.isLenient = false
  calendar.clear()
  return try {
    calendar.set(year, month - 1, day, time.hour, time.minute, 0)
    calendar.timeInMillis
  } catch (e: IllegalArgumentException) {
    // The 31st of February, which a model will produce when asked for "the end of February".
    // Lenient mode would quietly make that the 3rd of March.
    null
  }
}

/** A day on the calendar, as the parts an event needs. */
data class OnDay(val year: Int, val month: Int, val day: Int)

/**
 * The day [words] names, worked out from the real clock rather than by the model.
 *
 * This exists because the model got the date wrong by a fortnight while being told it, which
 * is a thing small models do: a date is exactly the sort of fact where training-data habit
 * beats an instruction. Having been wrong about today, it is wrong about tomorrow too, and an
 * event lands two weeks out with nothing on screen to suggest it.
 *
 * So the arithmetic moves here, where there is a clock. The model says "tomorrow" - which is a
 * translation of what it heard, and the thing it is reliable at - and this turns that into a
 * date. Absolute numbers still work for anything this cannot name.
 */
fun dayFromWords(words: String, now: Long, zone: TimeZone = TimeZone.getDefault()): OnDay? {
  val said = words.trim().lowercase().removePrefix("on ").removePrefix("next ").trim()
  if (said.isEmpty()) return null

  val offset =
    when {
      said == "today" || said == "tonight" || said == "this evening" -> 0
      said == "tomorrow" -> 1
      said == "the day after tomorrow" || said == "day after tomorrow" -> 2
      said.startsWith("+") -> said.drop(1).trim().toIntOrNull()
      said.startsWith("in ") ->
        said.removePrefix("in ").trim().removeSuffix("s").removeSuffix(" day").trim().toIntOrNull()
      else -> weekdayOffset(said, now, zone)
    } ?: return null

  if (offset !in 0..MAX_AHEAD_DAYS) return null

  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = now
  calendar.add(Calendar.DAY_OF_MONTH, offset)
  return OnDay(
    calendar.get(Calendar.YEAR),
    calendar.get(Calendar.MONTH) + 1,
    calendar.get(Calendar.DAY_OF_MONTH),
  )
}

/**
 * How many days until the next [name]day, or null if that is not a weekday.
 *
 * Never zero: "Tuesday" said on a Tuesday means the one coming, not the one half over. A
 * person who meant today would have said today.
 */
private fun weekdayOffset(name: String, now: Long, zone: TimeZone): Int? {
  val wanted = WEEKDAYS[name] ?: return null
  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = now
  val today = calendar.get(Calendar.DAY_OF_WEEK)
  val ahead = (wanted - today + 7) % 7
  return if (ahead == 0) 7 else ahead
}

private val WEEKDAYS =
  mapOf(
    "sunday" to Calendar.SUNDAY,
    "monday" to Calendar.MONDAY,
    "tuesday" to Calendar.TUESDAY,
    "wednesday" to Calendar.WEDNESDAY,
    "thursday" to Calendar.THURSDAY,
    "friday" to Calendar.FRIDAY,
    "saturday" to Calendar.SATURDAY,
  )

/** A year out is a mistake, not a plan somebody is making through an assistant. */
private const val MAX_AHEAD_DAYS = 366

/**
 * How today should be described to the model, at day resolution.
 *
 * Day resolution on purpose, and it is not laziness. The system prompt is part of what decides
 * which conversation a turn can be sent to on-device, so a prompt carrying the time of day
 * would build a new conversation every minute and throw away the history each time. The date
 * changes once a day, which costs one rebuild.
 *
 * Without this the model has no idea what day it is and cannot place "tomorrow" in a calendar
 * at all - it was guessing, and a 2B model's guess at the date is a year out as often as not.
 */
fun todayLine(now: Long, zone: TimeZone = TimeZone.getDefault()): String {
  val calendar = Calendar.getInstance(zone)
  calendar.timeInMillis = now
  val weekday =
    calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty()
  val month =
    calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()).orEmpty()
  return "Today is %s, %d %s %d.".format(
    weekday,
    calendar.get(Calendar.DAY_OF_MONTH),
    month,
    calendar.get(Calendar.YEAR),
  )
}

/**
 * Whether setting this without showing it to the user first is acceptable.
 *
 * The distinction is what the mistake costs, which is the same test the rest of this app
 * applies. A timer runs for minutes, is visible the moment it is set and is cancelled with one
 * tap - a wrong one is an annoyance. An alarm fires hours later, often in the night, and a
 * misheard "seven" for "eleven" is not noticed until it goes off. So a timer is set and an
 * alarm is handed to the clock app with the fields filled in, for a person to confirm.
 */
fun mayActWithoutConfirming(what: Timed): Boolean =
  when (what) {
    Timed.TIMER -> true
    Timed.ALARM -> false
    Timed.CALENDAR_EVENT -> false
  }

enum class Timed {
  TIMER,
  ALARM,
  CALENDAR_EVENT,
}

private const val MAX_TIMER_MINUTES = 24 * 60

private const val MIN_YEAR = 2000

private const val MAX_YEAR = 2100
