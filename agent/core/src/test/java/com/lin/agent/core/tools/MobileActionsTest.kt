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

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileActionsTest {

  @Test
  fun `the small model is asked only when one of its actions could apply`() {
    assertEquals(setOf("turn_on_flashlight", "turn_off_flashlight"), actionsFor("can you make the torch light up"))
    assertEquals(setOf("create_calendar_event"), actionsFor("put a meeting with Dana on Friday at 3"))
    assertEquals(setOf("show_map"), actionsFor("show me where the Louvre is on the map"))
    assertTrue(actionsFor("tell me about Paris").isEmpty())
    assertTrue(actionsFor("what is the capital of France").isEmpty())
    assertTrue(actionsFor("word ".repeat(40) + "calendar").isEmpty())
  }

  @Test
  fun `its picks become Lin's tools`() {
    val all = MOBILE_ACTIONS.map { it.name }.toSet()
    assertEquals(
      QuickCommand("phone_setting", mapOf("setting" to "flashlight", "state" to "on")),
      fromMobileAction("turn_on_flashlight", emptyMap(), all),
    )
    assertEquals(
      QuickCommand("navigate", mapOf("destination" to "Louvre Museum")),
      fromMobileAction("show_map", mapOf("query" to " Louvre Museum "), all),
    )
    assertEquals(
      QuickCommand(
        "add_to_calendar",
        mapOf("title" to "Team sync", "year" to 2026, "month" to 10, "day" to 23, "hour" to 15, "minute" to 30),
      ),
      fromMobileAction("create_calendar_event", mapOf("title" to "Team sync", "datetime" to "2026-10-23T15:30:00"), all),
    )
  }

  @Test
  fun `a pick that does not fit the request, or is incomplete, is thrown away`() {
    // Asked about a map, it picked the flashlight: a guess, not an answer.
    assertNull(fromMobileAction("turn_on_flashlight", emptyMap(), setOf("show_map")))
    assertNull(fromMobileAction("show_map", mapOf("query" to "  "), setOf("show_map")))
    assertNull(
      fromMobileAction("create_calendar_event", mapOf("title" to "x", "datetime" to "friday"), setOf("create_calendar_event"))
    )
    // Its other functions have no Lin equivalent and are left to the main model.
    assertNull(fromMobileAction("send_email", mapOf("to" to "a@b.c"), setOf("send_email")))
  }

  @Test
  fun `the prompt is the one it was trained with`() {
    assertEquals(
      "Current date and time given in YYYY-MM-DDTHH:MM:SS format: 2026-10-19T08:30:05\n" +
        "Day of week is Monday\nYou are a model that can do function calling with the following functions\n",
      mobileActionsPrompt(LocalDateTime.of(2026, 10, 19, 8, 30, 5, 123)),
    )
  }
}
