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

import com.google.ai.edge.gallery.tools.RemindersTool.Companion.parseWhen
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what the model passed into an actual moment.
 *
 * A reminder set for the wrong time is worse than one that was refused: it looks like it worked,
 * and the failure only shows up as silence at the moment it mattered. So a time that cannot be
 * read with confidence has to come back as null rather than as a guess.
 */
class ReminderTimeTest {

  /** A fixed Monday 14:30 local, so the assertions do not depend on when they run. */
  private val monday1430: Long =
    Calendar.getInstance()
      .apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.AUGUST)
        set(Calendar.DAY_OF_MONTH, 24)
        set(Calendar.HOUR_OF_DAY, 14)
        set(Calendar.MINUTE, 30)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      .timeInMillis

  private fun fieldsOf(millis: Long): Triple<Int, Int, Int> {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return Triple(
      calendar.get(Calendar.DAY_OF_MONTH),
      calendar.get(Calendar.HOUR_OF_DAY),
      calendar.get(Calendar.MINUTE),
    )
  }

  @Test
  fun readsMinutesFromNow() {
    assertEquals(monday1430 + 30 * 60_000L, parseWhen("30", monday1430))
    assertEquals(monday1430 + 30 * 60_000L, parseWhen("30 minutes", monday1430))
    assertEquals(monday1430 + 5 * 60_000L, parseWhen("5m", monday1430))
  }

  @Test
  fun readsHoursFromNow() {
    assertEquals(monday1430 + 2 * 3_600_000L, parseWhen("2 hours", monday1430))
    assertEquals(monday1430 + 3_600_000L, parseWhen("1h", monday1430))
  }

  @Test
  fun readsAClockTimeLaterToday() {
    val result = parseWhen("16:00", monday1430)
    assertNotNull(result)
    assertEquals(Triple(24, 16, 0), fieldsOf(result!!))
  }

  /**
   * A clock time already past means tomorrow. Scheduling it in the past would fire it instantly,
   * which reads as the feature being broken rather than the time being ambiguous.
   */
  @Test
  fun rollsAPastClockTimeToTomorrow() {
    val result = parseWhen("09:00", monday1430)
    assertNotNull(result)
    assertEquals(Triple(25, 9, 0), fieldsOf(result!!))
    assertTrue("must be in the future", result > monday1430)
  }

  /** The current minute has passed by the time anything fires, so it belongs to tomorrow too. */
  @Test
  fun rollsTheCurrentMinuteToTomorrow() {
    val result = parseWhen("14:30", monday1430)
    assertNotNull(result)
    assertEquals(Triple(25, 14, 30), fieldsOf(result!!))
  }

  @Test
  fun refusesTimesItCannotRead() {
    assertNull(parseWhen("", monday1430))
    assertNull(parseWhen("later", monday1430))
    assertNull(parseWhen("tomorrow morning", monday1430))
    assertNull(parseWhen("half past four", monday1430))
    assertNull(parseWhen("4pm", monday1430))
  }

  /** Out-of-range clock values are a misread, not a time. */
  @Test
  fun refusesImpossibleClockTimes() {
    assertNull(parseWhen("25:00", monday1430))
    assertNull(parseWhen("12:75", monday1430))
  }

  /** Zero and negative offsets would fire immediately or in the past. */
  @Test
  fun refusesOffsetsThatAreNotInTheFuture() {
    assertNull(parseWhen("0", monday1430))
    assertNull(parseWhen("0 minutes", monday1430))
    assertNull(parseWhen("-5", monday1430))
    assertNull(parseWhen("0h", monday1430))
  }
}
