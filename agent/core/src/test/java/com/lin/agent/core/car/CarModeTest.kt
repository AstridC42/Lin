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
