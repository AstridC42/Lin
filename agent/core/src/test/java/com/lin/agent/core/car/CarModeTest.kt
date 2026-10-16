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

package com.lin.agent.core.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who gets rung without a tap, and where. */
class CarModeTest {

  @Test
  fun `the careful setting stages every call`() {
    assertFalse(mayDialDirectly(DialPolicy.NEVER, inCar = false))
    assertFalse(mayDialDirectly(DialPolicy.NEVER, inCar = true))
  }

  @Test
  fun `in the car means in the car, and nowhere else`() {
    assertTrue(mayDialDirectly(DialPolicy.IN_THE_CAR, inCar = true))
    // The whole point of the middle setting: a phone in a pocket does not place calls.
    assertFalse(mayDialDirectly(DialPolicy.IN_THE_CAR, inCar = false))
  }

  @Test
  fun `always means always`() {
    assertTrue(mayDialDirectly(DialPolicy.ALWAYS, inCar = false))
    assertTrue(mayDialDirectly(DialPolicy.ALWAYS, inCar = true))
  }

  @Test
  fun `a stored name is read back`() {
    assertEquals(DialPolicy.IN_THE_CAR, dialPolicyNamed("IN_THE_CAR"))
    assertEquals(DialPolicy.ALWAYS, dialPolicyNamed("always"))
  }

  @Test
  fun `anything unreadable is the careful one`() {
    // A setting that cannot be read must not become permission to ring people.
    assertEquals(DialPolicy.NEVER, dialPolicyNamed(null))
    assertEquals(DialPolicy.NEVER, dialPolicyNamed(""))
    assertEquals(DialPolicy.NEVER, dialPolicyNamed("yes please"))
  }
}

class CarPaneTest {

  private val trouble = CarLine("No model", "Download one in Lin")
  private val listening = CarLine("Listening", "Say \"Hey Lin\"")
  private val reminder = CarLine("Dentist", "today at 18:30")
  private val messages = CarLine("2 waiting", "Ben, Dana")
  private val playing = CarLine("Playing", "Portishead")

  private val everything =
    mapOf(
      CarInfo.PLAYING to playing,
      CarInfo.TROUBLE to trouble,
      CarInfo.MESSAGES to messages,
      CarInfo.LISTENING to listening,
      CarInfo.REMINDER to reminder,
    )

  @Test
  fun `the order is the declared order, not the order they were put in`() {
    assertEquals(
      listOf(trouble, listening, reminder, messages, playing),
      carPane(everything, limit = 5),
    )
  }

  @Test
  fun `room for two means the two that matter`() {
    // Which two is the whole point: this is what Lin tells a driver when there is room for
    // nothing else.
    assertEquals(listOf(trouble, listening), carPane(everything, limit = 2))
  }

  @Test
  fun `no room means the microphone and nothing else`() {
    // The collapsed screen. Not an error - a real answer, and the one the user asked for.
    assertEquals(emptyList<CarLine>(), carPane(everything, limit = 0))
    assertEquals(emptyList<CarLine>(), carPane(everything, limit = -1))
  }

  @Test
  fun `a line with nothing to say is not a line`() {
    // "Next reminder -" costs a driver a glance and tells them nothing.
    val thin = mapOf(CarInfo.REMINDER to CarLine("  ", "today"), CarInfo.PLAYING to playing)
    assertEquals(listOf(playing), carPane(thin, limit = 4))
  }

  @Test
  fun `a detail is allowed to be empty when the title says it all`() {
    val bare = mapOf(CarInfo.MESSAGES to CarLine("2 messages waiting"))
    assertEquals(listOf(CarLine("2 messages waiting")), carPane(bare, limit = 4))
  }

  @Test
  fun `nothing to say is no rows`() {
    assertEquals(emptyList<CarLine>(), carPane(emptyMap(), limit = 4))
  }
}
