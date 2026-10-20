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

import com.lin.agent.core.tools.QuickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutinesTest {

  private fun task(trigger: Trigger, device: String = "", days: Set<Int> = emptySet(), lastRunAt: Long = 0, enabled: Boolean = true) =
    StandingTask(id = trigger.name + device, ask = "silent mode", hour = 8, minute = 0, days = days, enabled = enabled,
      trigger = trigger, device = device, lastRunAt = lastRunAt)

  @Test
  fun eventsStartTheRightRoutines() {
    val car = task(Trigger.BLUETOOTH_CONNECTED, "Car")
    val any = task(Trigger.BLUETOOTH_CONNECTED)
    val left = task(Trigger.BLUETOOTH_DISCONNECTED, "car")
    val charge = task(Trigger.CHARGING)
    val time = task(Trigger.TIME)
    val all = listOf(car, any, left, charge, time)
    val now = 100 * EVENT_GAP_MS
    assertEquals(listOf(car, any), firedBy(RoutineEvent.BluetoothConnected("My CAR audio"), all, 1, now))
    assertEquals(listOf(any), firedBy(RoutineEvent.BluetoothConnected("Galaxy Buds"), all, 1, now))
    assertEquals(listOf(left), firedBy(RoutineEvent.BluetoothDisconnected("Car"), all, 1, now))
    assertEquals(listOf(charge), firedBy(RoutineEvent.StartedCharging, all, 1, now))
  }

  @Test
  fun aFlappingConnectionFiresOnceAndDaysApply() {
    val now = 100 * EVENT_GAP_MS
    val recent = task(Trigger.CHARGING, lastRunAt = now - 60_000)
    assertTrue(firedBy(RoutineEvent.StartedCharging, listOf(recent), 1, now).isEmpty())
    val weekend = task(Trigger.CHARGING, days = setOf(6, 7))
    assertTrue(firedBy(RoutineEvent.StartedCharging, listOf(weekend), 1, now).isEmpty())
    assertEquals(1, firedBy(RoutineEvent.StartedCharging, listOf(weekend), 6, now).size)
    val off = task(Trigger.CHARGING, enabled = false)
    assertTrue(firedBy(RoutineEvent.StartedCharging, listOf(off), 1, now).isEmpty())
  }

  @Test
  fun eventRoutinesAreNeverDueByTheClock() {
    assertTrue(!isDue(task(Trigger.CHARGING), 1, 8 * 60, 1))
    assertTrue(isDue(task(Trigger.TIME), 1, 8 * 60, 1))
  }

  @Test
  fun plainCommandsRunWithoutAModel() {
    assertEquals(
      listOf(
        QuickCommand("sound_mode", mapOf("mode" to "silent")),
        QuickCommand("phone_setting", mapOf("setting" to "do not disturb", "state" to "on")),
      ),
      quickSteps("Silent mode and turn on do not disturb"),
    )
    assertEquals(2, quickSteps("volume 70%, then turn on bluetooth")?.size)
    assertNull(quickSteps("silent mode and tell me the weather"))
    assertNull(quickSteps("what's on today"))
  }

  @Test
  fun parsesWhatTheModelPasses() {
    assertEquals(Trigger.CHARGING, triggerFrom("when I plug it in"))
    assertEquals(Trigger.BLUETOOTH_CONNECTED, triggerFrom("when I connect to the car"))
    assertEquals(Trigger.BLUETOOTH_DISCONNECTED, triggerFrom("bluetooth disconnected"))
    assertEquals(Trigger.TIME, triggerFrom("at 7am"))
    assertNull(triggerFrom("purple"))
    assertEquals(7 to 30, timeFrom("7:30am"))
    assertEquals(19 to 0, timeFrom("7 pm"))
    assertEquals(0 to 15, timeFrom("12:15 am"))
    assertEquals(22 to 45, timeFrom("22:45"))
    assertEquals(12 to 0, timeFrom("noon"))
    assertNull(timeFrom("soon"))
    assertEquals(setOf(1, 2, 3, 4, 5), daysFrom("weekdays"))
    assertEquals(setOf(6, 7), daysFrom("weekends"))
    assertEquals(emptySet<Int>(), daysFrom("every day"))
    assertEquals(setOf(1, 3, 5), daysFrom("Mon, Wed and Fri"))
  }

  @Test
  fun storageKeepsTheNewFieldsAndReadsOldOnes() {
    val t = task(Trigger.BLUETOOTH_DISCONNECTED, "Car").copy(speak = true, lastRunAt = 42)
    assertEquals(listOf(t), tasksFrom(storedTasks(listOf(t))))
    val old = tasksFrom("""[{"id":"a","ask":"weather","hour":7,"minute":0}]""").single()
    assertEquals(Trigger.TIME, old.trigger)
    assertEquals(false, old.speak)
  }

  @Test
  fun describesARoutine() {
    assertEquals("\"silent mode\" when Car connects on weekdays",
      describeRoutine(task(Trigger.BLUETOOTH_CONNECTED, "Car", days = setOf(1, 2, 3, 4, 5))))
  }
}
