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

package com.lin.agent.core.diag

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class FaultsTest {

  @Test
  fun `a message that says something is used`() {
    assertEquals("the model file is corrupt", describeFault("IllegalStateException", "the model file is corrupt"))
  }

  @Test
  fun `a fault with no message falls back to its type`() {
    assertEquals("NullPointerException", describeFault("NullPointerException", null))
    assertEquals("NullPointerException", describeFault("NullPointerException", "   "))
  }

  @Test
  fun `a fault with neither still reads as a sentence`() {
    assertEquals("an unknown fault", describeFault(null, null))
    assertEquals("an unknown fault", describeFault("", ""))
  }

  @Test
  fun `running out of memory is said in words somebody can act on`() {
    val said = describeFault("OutOfMemoryError", "Failed to allocate a 71303168 byte allocation")
    // The byte count is true and useless to somebody holding a phone. What they can act on is
    // that the model is too big for it.
    assertFalse(said.contains("71303168"))
    assertTrue(said.contains("model"))
  }

  @Test
  fun `a trailing stop is dropped because this goes mid-sentence`() {
    assertEquals("it broke", describeFault("Exception", "it broke."))
  }

  @Test
  fun `a page of native stack is cut down`() {
    val huge = (1..100).joinToString(" ") { "frame$it" }
    val said = describeFault("RuntimeException", huge)
    assertTrue(said.length < huge.length)
    assertTrue(said.endsWith("…"))
    // Cut at a space, so the last thing shown is a whole word rather than "fram".
    assertFalse(said.removeSuffix("…").endsWith("fram"))
  }

  @Test
  fun `a message just under the limit is left alone`() {
    val fits = "x".repeat(180)
    assertEquals(fits, describeFault("Exception", fits))
  }
}
