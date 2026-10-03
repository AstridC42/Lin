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

package com.google.ai.edge.gallery.calendar

import java.util.Calendar

/**
 * Turning what a model wrote into a moment in the calendar.
 *
 * The failure that matters here is not rejection, it is a wrong answer that looks right. An
 * appointment silently filed on the wrong day is worse than one the assistant admits it could not
 * place, because nobody checks a confirmation they have already been given - so anything not
 * clearly understood returns null and the assistant asks.
 *
 * Free of Android so it can be tested, which is the only way to be sure about the cases that are
 * easy to get backwards: a time already past today meaning tomorrow, and a date given without a
 * year meaning the next one to come rather than the one just gone.
 */
object CalendarTime {

  private val ISO_DATE_TIME =
    Regex("^\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})[T ](\\d{1,2}):(\\d{2})\\s*$")
  private val ISO_DATE = Regex("^\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*$")
  private val DAY_MONTH_TIME = Regex("^\\s*(\\d{1,2})[/.](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})\\s*$")
  private val CLOCK = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$")
  private val TOMORROW = Regex("^\\s*tomorrow\\s+(\\d{1,2}):(\\d{2})\\s*$", RegexOption.IGNORE_CASE)
  private val TODAY = Regex("^\\s*today\\s+(\\d{1,2}):(\\d{2})\\s*$", RegexOption.IGNORE_CASE)
  private val IN_MINUTES = Regex("^\\s*in\\s+(\\d{1,4})\\s*(m|min|mins|minutes?)\\s*$", RegexOption.IGNORE_CASE)
  private val IN_HOURS = Regex("^\\s*in\\s+(\\d{1,3})\\s*(h|hr|hrs|hours?)\\s*$", RegexOption.IGNORE_CASE)

  /**
   * [value] as epoch milliseconds, or null when it cannot be read with confidence.
   *
   * @param nowMillis the moment to read relative expressions against.
   */
  fun parse(value: String, nowMillis: Long): Long? {
    ISO_DATE_TIME.matchEntire(value)?.let { m ->
      return at(
        year = m.int(1),
        month = m.int(2),
        day = m.int(3),
        hour = m.int(4),
        minute = m.int(5),
      )
    }
    // A bare date means the start of that day, which is what "on the fourteenth" means when no
    // time was given. The caller supplies the length.
    ISO_DATE.matchEntire(value)?.let { m ->
      return at(year = m.int(1), month = m.int(2), day = m.int(3), hour = 9, minute = 0)
    }
    DAY_MONTH_TIME.matchEntire(value)?.let { m ->
      // No year given, so the next one to come round: "3/1 09:00" asked in December is January.
      val candidate =
        at(
          year = yearOf(nowMillis),
          month = m.int(2),
          day = m.int(1),
          hour = m.int(3),
          minute = m.int(4),
        ) ?: return null
      return if (candidate >= nowMillis) candidate
      else
        at(
          year = yearOf(nowMillis) + 1,
          month = m.int(2),
          day = m.int(1),
          hour = m.int(3),
          minute = m.int(4),
        )
    }
    TOMORROW.matchEntire(value)?.let { m ->
      return dayOffsetAt(nowMillis, days = 1, hour = m.int(1), minute = m.int(2))
    }
    TODAY.matchEntire(value)?.let { m ->
      return dayOffsetAt(nowMillis, days = 0, hour = m.int(1), minute = m.int(2))
    }
    CLOCK.matchEntire(value)?.let { m ->
      val hour = m.int(1)
      val minute = m.int(2)
      val today = dayOffsetAt(nowMillis, days = 0, hour = hour, minute = minute) ?: return null
      // Already gone today, so tomorrow. Asking at five for "09:00" can only mean the morning, and
      // an appointment placed in the past is one nothing will ever remind anybody about.
      return if (today > nowMillis) today
      else dayOffsetAt(nowMillis, days = 1, hour = hour, minute = minute)
    }
    IN_MINUTES.matchEntire(value)?.let { m ->
      val minutes = m.int(1)
      return if (minutes <= 0) null else nowMillis + minutes * 60_000L
    }
    IN_HOURS.matchEntire(value)?.let { m ->
      val hours = m.int(1)
      return if (hours <= 0) null else nowMillis + hours * 3_600_000L
    }
    return null
  }

  private fun MatchResult.int(group: Int): Int = groupValues[group].toInt()

  private fun yearOf(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

  /** An absolute local time, or null when the numbers do not describe a real one. */
  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long? {
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
    val calendar =
      Calendar.getInstance().apply {
        // Rejects the thirty-first of February rather than rolling it into March, which would file
        // an appointment on a day nobody named.
        isLenient = false
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }
    return try {
      calendar.timeInMillis
    } catch (e: Exception) {
      null
    }
  }

  private fun dayOffsetAt(nowMillis: Long, days: Int, hour: Int, minute: Int): Long? {
    if (hour !in 0..23 || minute !in 0..59) return null
    return Calendar.getInstance()
      .apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, days)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      .timeInMillis
  }
}
