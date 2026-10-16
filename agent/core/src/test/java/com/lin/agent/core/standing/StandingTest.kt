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

package com.lin.agent.core.standing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingTest {

  private val morning =
    StandingTask(id = "a", ask = "What have I got on today?", hour = 8, minute = 0)

  private val today = 20_000L

  @Test
  fun `it runs at its time`() {
    assertTrue(isDue(morning, dayOfWeek = 3, minuteOfDay = 8 * 60, todayEpochDay = today))
  }

  @Test
  fun `it does not run before its time`() {
    assertFalse(isDue(morning, dayOfWeek = 3, minuteOfDay = 7 * 60 + 59, todayEpochDay = today))
  }

  @Test
  fun `a phone that was asleep still gets its briefing`() {
    // The worker behind this does not fire at an exact minute. A briefing at 08:40 because
    // the phone was asleep at 08:00 is still the briefing somebody wanted.
    assertTrue(isDue(morning, dayOfWeek = 3, minuteOfDay = 8 * 60 + 40, todayEpochDay = today))
  }

  @Test
  fun `a morning briefing never arrives at night`() {
    // The clause worth arguing about. Late is fine; hours late is a different message.
    assertFalse(isDue(morning, dayOfWeek = 3, minuteOfDay = 23 * 60, todayEpochDay = today))
  }

  @Test
  fun `the catch-up window has an edge and it is where it says`() {
    val edge = 8 * 60 + CATCH_UP_MINUTES
    assertTrue(isDue(morning, 3, edge, today))
    assertFalse(isDue(morning, 3, edge + 1, today))
  }

  @Test
  fun `it runs once a day, not once per check`() {
    // The worker wakes every quarter of an hour. Without this a daily briefing would arrive
    // twelve times before lunch.
    val alreadyRan = morning.copy(lastRunDay = today)
    assertFalse(isDue(alreadyRan, 3, 8 * 60 + 30, today))
    assertTrue(isDue(alreadyRan, 4, 8 * 60 + 30, today + 1))
  }

  @Test
  fun `switched off means switched off`() {
    assertFalse(isDue(morning.copy(enabled = false), 3, 8 * 60, today))
  }

  @Test
  fun `a task with nothing to ask does not run`() {
    assertFalse(isDue(morning.copy(ask = "   "), 3, 8 * 60, today))
  }

  @Test
  fun `no days means every day`() {
    // Empty rather than all seven, so a task made before this app knew about weekdays keeps
    // working rather than quietly matching nothing.
    for (day in 1..7) assertTrue("day $day", isDue(morning, day, 8 * 60, today))
  }

  @Test
  fun `chosen days are the only days`() {
    val weekdays = morning.copy(days = setOf(1, 2, 3, 4, 5))
    assertTrue(isDue(weekdays, 5, 8 * 60, today))
    assertFalse(isDue(weekdays, 6, 8 * 60, today))
    assertFalse(isDue(weekdays, 7, 8 * 60, today))
  }

  @Test
  fun `only the due ones are returned, in the order they were made`() {
    val tasks =
      listOf(
        morning,
        morning.copy(id = "b", hour = 18),
        morning.copy(id = "c", enabled = false),
        morning.copy(id = "d", hour = 8, minute = 30),
      )
    assertEquals(listOf("a", "d"), dueNow(tasks, 3, 8 * 60 + 35, today).map { it.id })
  }

  @Test
  fun `marking one as run leaves the others alone`() {
    val tasks = listOf(morning, morning.copy(id = "b"))
    val after = markRun(tasks, "a", today)
    assertEquals(today, after.first { it.id == "a" }.lastRunDay)
    assertEquals(0L, after.first { it.id == "b" }.lastRunDay)
  }

  @Test
  fun `the time reads as a clock`() {
    assertEquals("08:00", clockTime(morning))
    assertEquals("18:05", clockTime(morning.copy(hour = 18, minute = 5)))
  }

  // ---- storage ----

  @Test
  fun `a task survives a round trip`() {
    val tasks = listOf(morning.copy(days = setOf(1, 5), lastRunDay = 42))
    assertEquals(tasks, tasksFrom(storedTasks(tasks)))
  }

  @Test
  fun `nothing stored is no tasks`() {
    assertTrue(tasksFrom(null).isEmpty())
    assertTrue(tasksFrom("").isEmpty())
  }

  @Test
  fun `an unreadable value is no tasks rather than a crash`() {
    // Read by a worker nothing is waiting on. A damaged value should stop the routines, not
    // the process that was going to run them.
    assertTrue(tasksFrom("{not json").isEmpty())
    assertTrue(tasksFrom("""[{"ask":"x"}]""").isEmpty())
    assertTrue(tasksFrom("""[{"id":"x"}]""").isEmpty())
  }

  @Test
  fun `a nonsense time is corrected rather than dropped`() {
    // A task with a bad hour should run at a sane time, not vanish from a list the user can
    // see and cannot fix.
    val back = tasksFrom("""[{"id":"a","ask":"x","hour":99,"minute":-4}]""").single()
    assertEquals(23, back.hour)
    assertEquals(0, back.minute)
  }

  @Test
  fun `a day outside the week is ignored`() {
    val back = tasksFrom("""[{"id":"a","ask":"x","days":[0,3,9]}]""").single()
    assertEquals(setOf(3), back.days)
  }

  @Test
  fun `the same id twice is one task`() {
    val stored = """[{"id":"a","ask":"one"},{"id":"a","ask":"two"}]"""
    assertEquals(1, tasksFrom(stored).size)
  }

  @Test
  fun `a task stored by a newer build keeps the fields this one knows`() {
    val back = tasksFrom("""[{"id":"a","ask":"x","hour":9,"colour":"blue"}]""").single()
    assertEquals(9, back.hour)
    assertEquals("x", back.ask)
  }
}
