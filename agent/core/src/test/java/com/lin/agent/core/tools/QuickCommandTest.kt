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

package com.lin.agent.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickCommandTest {

  private fun switch(setting: String, state: String) =
    QuickCommand("phone_setting", mapOf("setting" to setting, "state" to state))

  private fun open(app: String) = QuickCommand("open_app", mapOf("app" to app))

  @Test
  fun `plain switch commands run at once`() {
    assertEquals(switch("flashlight", "on"), quickCommand("Turn on the flashlight"))
    assertEquals(switch("flashlight", "on"), quickCommand("flashlight on"))
    assertEquals(switch("flashlight", "off"), quickCommand("Torch off please."))
    assertEquals(switch("flashlight", "on"), quickCommand("Hey Lin turn the flash on"))
    assertEquals(switch("wi-fi", "off"), quickCommand("turn off wifi"))
    assertEquals(switch("bluetooth", "on"), quickCommand("enable bluetooth"))
    assertEquals(switch("do not disturb", "on"), quickCommand("turn on do not disturb"))
    assertEquals(switch("airplane mode", "off"), quickCommand("switch airplane mode off"))
  }

  @Test
  fun `plain open commands run at once`() {
    assertEquals(open("camera"), quickCommand("open camera"))
    assertEquals(open("gallery"), quickCommand("Open the gallery"))
    assertEquals(open("google maps"), quickCommand("launch Google Maps"))
    assertEquals(open("clock"), quickCommand("open the clock app"))
    assertEquals(open("spotify"), quickCommand("please open spotify"))
  }

  @Test
  fun `anything that needs judgement goes to the model`() {
    // Questions, second clauses and timing are not plain commands.
    assertNull(quickCommand("is the flashlight on?"))
    assertNull(quickCommand("turn on the flashlight and open camera"))
    assertNull(quickCommand("turn on the flashlight in five minutes"))
    assertNull(quickCommand("open spotify, then play jazz"))
    // Neither on nor off, or both.
    assertNull(quickCommand("flashlight"))
    assertNull(quickCommand("turn the flashlight on and off"))
    // Something more than the switch.
    assertNull(quickCommand("turn on the flashlight brightness to max"))
    // Not apps.
    assertNull(quickCommand("open the door"))
    assertNull(quickCommand("start a timer"))
    assertNull(quickCommand("open a new chat"))
    assertNull(quickCommand("open the photos from my trip to rome"))
    assertNull(quickCommand("what's the weather"))
    assertNull(quickCommand(""))
  }

  @Test
  fun `the reply is the tool's first sentence, for a person`() {
    assertEquals("The flashlight is on.", quickReply("Done - the flashlight is on."))
    assertEquals("Wi-fi is on.", quickReply("Done - wi-fi is on. Lin set it directly."))
    assertEquals(
      "The phone's own wi-fi panel is open, with the switch on it.",
      quickReply("Done - the phone's own wi-fi panel is open, with the switch on it. Android has not..."),
    )
    assertEquals("Google Maps is open.", quickReply("Done - Google Maps is open."))
  }
}
