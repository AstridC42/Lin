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

package com.lin.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing a settings screen that exists.
 *
 * The failure being prevented is specific: starting an intent nothing handles throws, and the
 * button that would do it sits on the screen somebody opened because something was already not
 * working.
 */
class AssistantRoleTest {

  @Test
  fun `the assistant screen is used where it exists`() {
    assertEquals(
      "android.settings.VOICE_INPUT_SETTINGS",
      AssistantRole.firstResolvable { true },
    )
  }

  @Test
  fun `a phone without it falls through to the next screen`() {
    val action =
      AssistantRole.firstResolvable { it != "android.settings.VOICE_INPUT_SETTINGS" }
    assertEquals("android.settings.MANAGE_DEFAULT_APPS_SETTINGS", action)
  }

  @Test
  fun `the last resort is the top of settings`() {
    val action = AssistantRole.firstResolvable { it == "android.settings.SETTINGS" }
    assertEquals("android.settings.SETTINGS", action)
  }

  @Test
  fun `nothing resolvable is said rather than assumed`() {
    assertNull(AssistantRole.firstResolvable { false })
  }

  @Test
  fun `the list ends somewhere every phone has`() {
    // Not decoration: the fallback chain is only safe because its last entry is the one
    // screen that cannot be missing.
    assertEquals("android.settings.SETTINGS", AssistantRole.settingsScreens.last())
    assertTrue(AssistantRole.settingsScreens.size >= 2)
  }
}
