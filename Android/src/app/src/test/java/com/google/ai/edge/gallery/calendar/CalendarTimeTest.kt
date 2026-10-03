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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placing an appointment at the moment somebody meant.
 *
 * The failure to guard against is not a rejection - it is a wrong answer that reads as right. An
 * appointment quietly filed on the wrong day is worse than one the assistant says it could not
 * place, because nobody re-checks a confirmation they have already been given.
 */
class CalendarTimeTest {

  /** A fixed Tuesday afternoon to read everything relative to. */
  private val now =
    Calendar.getInstance()
      .apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 1, 14, 0, 0)
      }
      .timeInMillis

  private fun fieldsOf(millis: Long): List<Int> =
    Calendar.getInstance().apply { timeInMillis = millis }.let {
      listOf(
        it.get(Calendar.YEAR),
        it.get(Calendar.MONTH) + 1,
        it.get(Calendar.DAY_OF_MONTH),
        it.get(Calendar.HOUR_OF_DAY),
        it.get(Calendar.MINUTE),
      )
    }

  @Test
  fun placesAFullDateAndTime() {
    val parsed = CalendarTime.parse("2026-09-04 14:30", now)
    assertNotNull(parsed)
    assertEquals(listOf(2026, 9, 4, 14, 30), fieldsOf(parsed!!))
  }

  /** The same instant written the way an ISO timestamp is. */
  @Test
  fun acceptsATBetweenTheDateAndTheTime() {
    assertEquals(CalendarTime.parse("2026-09-04 14:30", now), CalendarTime.parse("2026-09-04T14:30", now))
  }

  /** A clock time still to come today means today. */
  @Test
  fun placesATimeLaterTodayToday() {
    val parsed = CalendarTime.parse("16:30", now)
    assertNotNull(parsed)
    assertEquals(listOf(2026, 9, 1, 16, 30), fieldsOf(parsed!!))
  }

  /**
   * A clock time already gone means tomorrow. Asking at two in the afternoon for "09:00" can only
   * mean the morning, and an appointment placed in the past is one nothing will ever raise.
   */
  @Test
  fun rollsATimeAlreadyGoneToTomorrow() {
    val parsed = CalendarTime.parse("09:00", now)
    assertNotNull(parsed)
    assertEquals(listOf(2026, 9, 2, 9, 0), fieldsOf(parsed!!))
    assertTrue(parsed > now)
  }

  @Test
  fun understandsTodayAndTomorrowByName() {
    assertEquals(listOf(2026, 9, 1, 9, 0), fieldsOf(CalendarTime.parse("today 09:00", now)!!))
    assertEquals(listOf(2026, 9, 2, 9, 0), fieldsOf(CalendarTime.parse("tomorrow 09:00", now)!!))
  }

  /** "today 09:00" is deliberately allowed to be in the past: it was asked for explicitly. */
  @Test
  fun leavesAnExplicitTodayWhereItWasPut() {
    assertTrue(CalendarTime.parse("today 09:00", now)!! < now)
  }

  @Test
  fun understandsRelativeTimes() {
    assertEquals(now + 30 * 60_000L, CalendarTime.parse("in 30 minutes", now))
    assertEquals(now + 2 * 3_600_000L, CalendarTime.parse("in 2 hours", now))
  }

  /** A day and month with no year means the next one to come, not the one just gone. */
  @Test
  fun readsADayAndMonthAsTheNextOneToCome() {
    val january = CalendarTime.parse("3/1 09:00", now)
    assertNotNull(january)
    assertEquals(listOf(2027, 1, 3, 9, 0), fieldsOf(january!!))

    val laterThisYear = CalendarTime.parse("4/12 09:00", now)
    assertEquals(listOf(2026, 12, 4, 9, 0), fieldsOf(laterThisYear!!))
  }

  /** A bare date gets a sensible hour rather than midnight, which nobody means by "on the 4th". */
  @Test
  fun givesABareDateAWorkingHour() {
    assertEquals(listOf(2026, 9, 4, 9, 0), fieldsOf(CalendarTime.parse("2026-09-04", now)!!))
  }

  /** A day that does not exist must be refused, not rolled forward into the next month. */
  @Test
  fun refusesADayThatDoesNotExist() {
    assertNull(CalendarTime.parse("2026-02-31 10:00", now))
    assertNull(CalendarTime.parse("2026-13-01 10:00", now))
    assertNull(CalendarTime.parse("2026-09-04 25:00", now))
    assertNull(CalendarTime.parse("2026-09-04 10:99", now))
  }

  /** Anything else returns null so the assistant asks, rather than filing a guess. */
  @Test
  fun refusesWhatItCannotRead() {
    assertNull(CalendarTime.parse("sometime next week", now))
    assertNull(CalendarTime.parse("", now))
    assertNull(CalendarTime.parse("in 0 minutes", now))
    assertNull(CalendarTime.parse("half past three", now))
  }
}
