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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolChoiceTest {

  @Test
  fun `nothing stored means nothing is switched off`() {
    assertEquals(emptySet<String>(), disabledFrom(null))
    assertEquals(emptySet<String>(), disabledFrom(""))
    assertEquals(emptySet<String>(), disabledFrom("\n\n  \n"))
  }

  @Test
  fun `a stored list reads back`() {
    assertEquals(setOf("weather", "play_music"), disabledFrom("weather\nplay_music"))
  }

  @Test
  fun `stray whitespace does not make a different tool name`() {
    // The stored string is edited by the settings import as well as by the screen, and a name
    // with a space on the end matches nothing - so the tool silently comes back on.
    assertEquals(setOf("weather"), disabledFrom("  weather  \n"))
  }

  @Test
  fun `a tool nobody has heard of is on`() {
    // The property that makes the off-list the right thing to store: a tool that ships in a
    // later version is in nobody's saved list, and so arrives switched on.
    val disabled = disabledFrom("weather")
    assertTrue(isEnabled("send_message", disabled))
    assertFalse(isEnabled("weather", disabled))
  }

  @Test
  fun `switching a tool off and on again leaves the store as it was`() {
    val start = disabledFrom("play_music\nweather")
    val off = withTool(start, "read_screen", enabled = false)
    val backOn = withTool(off, "read_screen", enabled = true)
    assertEquals(storedFrom(start), storedFrom(backOn))
  }

  @Test
  fun `the stored form is sorted and deduplicated`() {
    assertEquals("a\nb\nc", storedFrom(setOf("c", "a", "b", "a")))
  }

  @Test
  fun `blank names never reach the store`() {
    assertEquals("weather", storedFrom(setOf("weather", "", "   ")))
  }

  @Test
  fun `turning on a tool that was never off changes nothing`() {
    val disabled = setOf("weather")
    assertEquals(disabled, withTool(disabled, "clock", enabled = true))
  }

  @Test
  fun `a round trip through storage keeps the same set`() {
    val disabled = setOf("weather", "play_music", "admin.tools.list", "DATA_EXPORT_v2")
    assertEquals(disabled, disabledFrom(storedFrom(disabled)))
  }

  @Test
  fun `a name with a comma in it survives, because the separator is a newline`() {
    // MCP allows dots, hyphens and underscores in tool names and only discourages the rest;
    // a comma-separated store would split one name into two that match nothing.
    assertEquals(setOf("weird,name"), disabledFrom(storedFrom(setOf("weird,name"))))
  }
}
