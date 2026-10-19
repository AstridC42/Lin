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

package com.lin.agent.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading "turn the flashlight on" without turning the wrong thing off.
 *
 * Both halves can fail quietly. A switch guessed wrong acts on something the user did not name,
 * and a state guessed wrong acts when they were only asking.
 */
class SwitchesTest {

  @Test
  fun `the words people use for the torch`() {
    for (said in listOf("flashlight", "the flash light", "torch", "flash", "light", "the light")) {
      assertEquals(said, Switch.FLASHLIGHT, switchNamed(said))
    }
  }

  @Test
  fun `a light that is not the torch is not matched`() {
    // "night light" and "light mode" are other settings entirely, and the torch coming on for
    // either is the kind of surprise that gets an assistant turned off.
    assertNull(switchNamed("night light"))
    assertNull(switchNamed("light mode"))
  }

  @Test
  fun `the words people use for the rest`() {
    assertEquals(Switch.WIFI, switchNamed("wi-fi"))
    assertEquals(Switch.WIFI, switchNamed("the internet"))
    assertEquals(Switch.BLUETOOTH, switchNamed("Bluetooth"))
    assertEquals(Switch.BLUETOOTH, switchNamed("BT"))
    assertEquals(Switch.NFC, switchNamed("NFC"))
    assertEquals(Switch.DO_NOT_DISTURB, switchNamed("do not disturb"))
    assertEquals(Switch.DO_NOT_DISTURB, switchNamed("silent"))
    assertEquals(Switch.AIRPLANE_MODE, switchNamed("airplane mode"))
  }

  @Test
  fun `something Lin cannot touch is not quietly turned into something it can`() {
    // Mobile data is not Wi-Fi. Saying so is the only honest answer; matching it to the
    // nearest thing Lin can reach would turn the wrong radio off.
    assertNull(switchNamed("mobile data"))
    assertNull(switchNamed("hotspot"))
    assertNull(switchNamed(""))
    assertNull(switchNamed("the kettle"))
  }

  @Test
  fun `on and off`() {
    assertEquals(true, wantedOn("on"))
    assertEquals(true, wantedOn("turn it on"))
    assertEquals(true, wantedOn("enable"))
    assertEquals(false, wantedOn("off"))
    assertEquals(false, wantedOn("turn off"))
    assertEquals(false, wantedOn("disabled"))
  }

  @Test
  fun `off wins when both words are present`() {
    // "turn on do not disturb off" is nonsense, but a model that echoes the user can produce
    // it. Stopping is the safer reading of a contradiction.
    assertEquals(false, wantedOn("on off"))
  }

  @Test
  fun `a question is not an instruction`() {
    // The bug this prevents: "on" inside "phone" reading a question as an order, and the
    // torch coming on in somebody's pocket.
    assertNull(wantedOn("is the flashlight on my phone"))
    assertNull(wantedOn("what about the front camera"))
    assertNull(wantedOn(""))
  }

  @Test
  fun `only the torch can simply be done`() {
    assertEquals(Reach.DIRECT, reachOf(Switch.FLASHLIGHT))
  }

  @Test
  fun `the radios can only be opened, whatever the SDK still compiles`() {
    // setWifiEnabled and BluetoothAdapter.enable are still in the SDK and still compile; they
    // return false rather than failing loudly. This table is what stops Lin calling them and
    // reporting success.
    assertEquals(Reach.OPENS_IT, reachOf(Switch.WIFI))
    assertEquals(Reach.OPENS_IT, reachOf(Switch.BLUETOOTH))
    assertEquals(Reach.OPENS_IT, reachOf(Switch.NFC))
    assertEquals(Reach.OPENS_IT, reachOf(Switch.AIRPLANE_MODE))
  }

  @Test
  fun `do not disturb needs its own grant first`() {
    assertEquals(Reach.NEEDS_PERMISSION, reachOf(Switch.DO_NOT_DISTURB, hasPolicyAccess = false))
    assertEquals(Reach.DIRECT, reachOf(Switch.DO_NOT_DISTURB, hasPolicyAccess = true))
  }
}
