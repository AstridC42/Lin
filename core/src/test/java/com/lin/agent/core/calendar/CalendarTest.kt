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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a calendar, and refusing times a small model should not be trusted with. */
class CalendarTest {

  private val jerusalem: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")
  private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long {
    val calendar = Calendar.getInstance(zone, Locale.US)
    calendar.clear()
    calendar.set(year, month - 1, day, hour, minute, 0)
    return calendar.timeInMillis
  }

  /** Midnight UTC on a date, which is how Android stores an all-day event. */
  private fun utcMidnight(year: Int, month: Int, day: Int): Long =
    at(year, month, day, 0, 0, TimeZone.getTimeZone("UTC"))

  // ---- the bug this file mostly exists for ----

  /**
   * An all-day event is stored at midnight UTC, not midnight where the person is.
   *
   * Read in the local zone it lands at 03:00 on the right day in Jerusalem and 19:00 on the
   * *previous* day in New York. An assistant that says somebody's birthday was yesterday is
   * worse than one with no calendar at all.
   */
  @Test
  fun anAllDayEventIsOnTheSameDayWhereverThePhoneIs() {
    val birthday =
      CalEvent("Otto's birthday", utcMidnight(2026, 10, 14), utcMidnight(2026, 10, 15), allDay = true)
    assertEquals("2026-10-14", dayOf(birthday, jerusalem))
    assertEquals("2026-10-14", dayOf(birthday, newYork))
  }

  /** A timed event is the opposite: it must be read where the person is. */
  @Test
  fun aTimedEventIsReadInTheLocalZone() {
    val standup = CalEvent("Standup", at(2026, 10, 14, 9, 0, jerusalem), at(2026, 10, 14, 9, 30, jerusalem))
    assertEquals("2026-10-14", dayOf(standup, jerusalem))
  }

  // ---- what the model is shown ----

  @Test
  fun eventsAreGroupedUnderTheirDay() {
    val now = at(2026, 10, 14, 8, 0, jerusalem)
    val text =
      describeEvents(
        listOf(
          CalEvent("Standup", at(2026, 10, 14, 9, 0, jerusalem), at(2026, 10, 14, 9, 30, jerusalem)),
          CalEvent("Review", at(2026, 10, 15, 11, 0, jerusalem), at(2026, 10, 15, 12, 0, jerusalem)),
        ),
        now,
        jerusalem,
      )
    assertTrue(text, text.contains("Today"))
    assertTrue(text, text.contains("Tomorrow"))
    assertTrue(text, text.contains("09:00-09:30 Standup"))
  }

  @Test
  fun anAllDayEventSaysSoRatherThanShowingAMeaninglessTime() {
    val now = at(2026, 10, 14, 8, 0, jerusalem)
    val text =
      describeEvents(
        listOf(CalEvent("Otto's birthday", utcMidnight(2026, 10, 14), utcMidnight(2026, 10, 15), allDay = true)),
        now,
        jerusalem,
      )
    assertTrue(text, text.contains("All day: Otto's birthday"))
    assertFalse(text, text.contains("00:00"))
  }

  /** A zero-length event is how a reminder often arrives; "14:00-14:00" is noise. */
  @Test
  fun anInstantEventShowsOneTime() {
    val now = at(2026, 10, 14, 8, 0, jerusalem)
    val start = at(2026, 10, 14, 14, 0, jerusalem)
    val text = describeEvents(listOf(CalEvent("Dentist", start, start)), now, jerusalem)
    assertTrue(text, text.contains("14:00 Dentist"))
    assertFalse(text, text.contains("14:00-14:00"))
  }

  @Test
  fun aLocationIsIncludedWhenThereIsOne() {
    val now = at(2026, 10, 14, 8, 0, jerusalem)
    val start = at(2026, 10, 14, 14, 0, jerusalem)
    val text =
      describeEvents(listOf(CalEvent("Dentist", start, start, location = "Main Street")), now, jerusalem)
    assertTrue(text, text.contains("(Main Street)"))
  }

  @Test
  fun anEmptyCalendarSaysSoRatherThanNothing() {
    assertTrue(describeEvents(emptyList(), 0L, jerusalem).contains("Nothing"))
  }

  /** Two hundred entries would be the whole context window. */
  @Test
  fun aVeryFullCalendarIsCutAndSaysHowMuchWasLeftOut() {
    val now = at(2026, 10, 14, 8, 0, jerusalem)
    val many =
      (1..100).map {
        val start = at(2026, 10, 14, 0, 0, jerusalem) + it * 60_000L
        CalEvent("Event $it", start, start)
      }
    val text = describeEvents(many, now, jerusalem)
    assertTrue(text, text.contains("more are not shown"))
    assertFalse(text, text.contains("Event 99 "))
  }

  // ---- times a model should not be trusted with ----

  @Test
  fun anHourOutsideTheDayIsRefused() {
    assertNull(clockTime(25, 0))
    assertNull(clockTime(-1, 0))
    assertNull(clockTime(7, 61))
    assertNull(clockTime(null, 30))
  }

  @Test
  fun anOrdinaryTimeIsAccepted() {
    assertEquals(ClockTime(7, 30), clockTime(7, 30))
    assertEquals(ClockTime(0, 0), clockTime(0, 0))
    assertEquals(ClockTime(23, 59), clockTime(23, 59))
    // Minutes default to the hour.
    assertEquals(ClockTime(7, 0), clockTime(7, null))
  }

  /** "Ten" misread as "ten thousand" should not set something that fires next week. */
  @Test
  fun aTimerIsBoundedToADay() {
    assertEquals(10, timerMinutes(10))
    assertEquals(1440, timerMinutes(1440))
    assertNull(timerMinutes(1441))
    assertNull(timerMinutes(10_000))
    assertNull(timerMinutes(0))
    assertNull(timerMinutes(-5))
    assertNull(timerMinutes(null))
  }

  /** A model asked for "the end of February" produces the 31st. */
  @Test
  fun aDateThatDoesNotExistIsRefusedRatherThanRolledForward() {
    assertNull(startOfEvent(2026, 2, 31, 9, 0, jerusalem))
    assertNull(startOfEvent(2026, 13, 1, 9, 0, jerusalem))
    assertNull(startOfEvent(2026, 0, 1, 9, 0, jerusalem))
  }

  @Test
  fun aRealDateBecomesAMoment() {
    val start = startOfEvent(2026, 10, 14, 15, 0, jerusalem)
    assertEquals(at(2026, 10, 14, 15, 0, jerusalem), start)
  }

  @Test
  fun aYearFarOutsideReasonIsRefused() {
    assertNull(startOfEvent(26, 10, 14, 9, 0, jerusalem))
    assertNull(startOfEvent(9999, 10, 14, 9, 0, jerusalem))
  }

  @Test
  fun anEventWithNoTimeStartsAtMidnight() {
    assertEquals(at(2026, 10, 14, 0, 0, jerusalem), startOfEvent(2026, 10, 14, null, null, jerusalem))
  }

  // ---- the date, worked out here rather than by the model ----

  /**
   * The bug this exists for.
   *
   * The model was told today's date and still said one a fortnight earlier, which is what
   * small models do with dates: training-data habit beats an instruction. Having been wrong
   * about today it is wrong about tomorrow too, and an event lands two weeks out with nothing
   * on screen to suggest it.
   */
  @Test
  fun todayAndTomorrowComeFromTheClock() {
    val now = at(2026, 10, 14, 19, 0, jerusalem)
    assertEquals(OnDay(2026, 10, 14), dayFromWords("today", now, jerusalem))
    assertEquals(OnDay(2026, 10, 15), dayFromWords("tomorrow", now, jerusalem))
    assertEquals(OnDay(2026, 10, 16), dayFromWords("the day after tomorrow", now, jerusalem))
  }

  @Test
  fun aWeekdayMeansTheNextOne() {
    // The 14th of October 2026 is a Wednesday.
    val now = at(2026, 10, 14, 9, 0, jerusalem)
    assertEquals(OnDay(2026, 10, 15), dayFromWords("thursday", now, jerusalem))
    assertEquals(OnDay(2026, 10, 19), dayFromWords("monday", now, jerusalem))
  }

  /** "Wednesday" said on a Wednesday means the one coming; today would have been "today". */
  @Test
  fun theSameWeekdayMeansNextWeekRatherThanTodayHalfOver() {
    val now = at(2026, 10, 14, 9, 0, jerusalem)
    assertEquals(OnDay(2026, 10, 21), dayFromWords("wednesday", now, jerusalem))
  }

  @Test
  fun aCountOfDaysWorks() {
    val now = at(2026, 10, 14, 9, 0, jerusalem)
    assertEquals(OnDay(2026, 10, 17), dayFromWords("in 3 days", now, jerusalem))
    assertEquals(OnDay(2026, 10, 17), dayFromWords("+3", now, jerusalem))
  }

  /** Crossing a month and a year is where off-by-one arithmetic shows up. */
  @Test
  fun crossingAMonthAndAYearIsHandledByTheCalendarRatherThanByHand() {
    assertEquals(
      OnDay(2026, 11, 1),
      dayFromWords("tomorrow", at(2026, 10, 31, 23, 0, jerusalem), jerusalem),
    )
    assertEquals(
      OnDay(2027, 1, 1),
      dayFromWords("tomorrow", at(2026, 12, 31, 23, 0, jerusalem), jerusalem),
    )
  }

  @Test
  fun fillerAroundTheWordIsIgnored() {
    val now = at(2026, 10, 14, 9, 0, jerusalem)
    assertEquals(OnDay(2026, 10, 15), dayFromWords("  on Thursday ", now, jerusalem))
    assertEquals(OnDay(2026, 10, 19), dayFromWords("next monday", now, jerusalem))
  }

  @Test
  fun somethingThatNamesNoDayIsRefusedRatherThanGuessed() {
    val now = at(2026, 10, 14, 9, 0, jerusalem)
    assertNull(dayFromWords("", now, jerusalem))
    assertNull(dayFromWords("soon", now, jerusalem))
    assertNull(dayFromWords("whenever", now, jerusalem))
  }

  /** A year out is a mistake, not a plan somebody is making through an assistant. */
  @Test
  fun anAbsurdOffsetIsRefused() {
    val now = at(2026, 10, 14, 9, 0, jerusalem)
    assertNull(dayFromWords("+100000", now, jerusalem))
    assertNull(dayFromWords("-1", now, jerusalem))
  }

  // ---- what may happen without being shown first ----

  /**
   * The distinction is what the mistake costs.
   *
   * A timer is visible immediately and cancelled with one tap. An alarm fires hours later,
   * often overnight, and a misheard "seven" for "eleven" is found out by being woken.
   */
  @Test
  fun aTimerMayBeSetButAnAlarmIsHandedOver() {
    assertTrue(mayActWithoutConfirming(Timed.TIMER))
    assertFalse(mayActWithoutConfirming(Timed.ALARM))
    assertFalse(mayActWithoutConfirming(Timed.CALENDAR_EVENT))
  }

  // ---- the window to look in ----

  /**
   * Midnight where the person is, not UTC midnight.
   *
   * `now - now % DAY_MS` is the obvious line and is wrong everywhere but Greenwich: three
   * hours out in Jerusalem, and on the wrong day entirely in New York between midnight and
   * dawn - so "what is on today" would answer for yesterday.
   */
  @Test
  fun todayStartsAtLocalMidnight() {
    val evening = at(2026, 10, 14, 19, 25, jerusalem)
    assertEquals(at(2026, 10, 14, 0, 0, jerusalem), startOfToday(evening, jerusalem))

    // The case that catches the UTC version: after midnight, west of Greenwich.
    val smallHours = at(2026, 10, 14, 1, 30, newYork)
    assertEquals(at(2026, 10, 14, 0, 0, newYork), startOfToday(smallHours, newYork))
  }

  @Test
  fun oneDayAheadIsTheRestOfToday() {
    val now = at(2026, 10, 14, 19, 25, jerusalem)
    val end = endOfDaysAhead(now, 1, jerusalem)
    assertTrue(end > now)
    assertEquals("2026-10-14", dayAt(end, jerusalem))
  }

  @Test
  fun sevenDaysAheadReachesTheSeventhEvening() {
    val now = at(2026, 10, 14, 19, 25, jerusalem)
    assertEquals("2026-10-20", dayAt(endOfDaysAhead(now, 7, jerusalem), jerusalem))
  }

  /**
   * Counted in calendar days, not in milliseconds.
   *
   * A day is 23 or 25 hours across a clock change, so adding days * 24h lands short and drops
   * whatever is last on the final evening.
   */
  @Test
  fun aClockChangeDoesNotShortenTheWindow() {
    // Israel's clocks go back on the last Sunday of October.
    val before = at(2026, 10, 24, 12, 0, jerusalem)
    val end = endOfDaysAhead(before, 3, jerusalem)
    assertEquals("2026-10-26", dayAt(end, jerusalem))
  }

  // ---- telling the model what day it is ----

  @Test
  fun todayIsStatedWithTheWeekdayAndTheYear() {
    val line = todayLine(at(2026, 10, 14, 19, 25, jerusalem), jerusalem)
    assertTrue(line, line.contains("14"))
    assertTrue(line, line.contains("2026"))
  }

  /**
   * At day resolution, and that is load-bearing.
   *
   * The system prompt decides which conversation a turn can be sent to on-device, so a prompt
   * carrying the time of day would build a new conversation every minute and throw the history
   * away each time.
   */
  @Test
  fun todayDoesNotChangeThroughTheDay() {
    val morning = todayLine(at(2026, 10, 14, 6, 0, jerusalem), jerusalem)
    val night = todayLine(at(2026, 10, 14, 23, 59, jerusalem), jerusalem)
    assertEquals(morning, night)
  }

  @Test
  fun todayChangesWhenTheDayDoes() {
    val tuesday = todayLine(at(2026, 10, 14, 12, 0, jerusalem), jerusalem)
    val wednesday = todayLine(at(2026, 10, 15, 12, 0, jerusalem), jerusalem)
    assertTrue(tuesday != wednesday)
  }
}
