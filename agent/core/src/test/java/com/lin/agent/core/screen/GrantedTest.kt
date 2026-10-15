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

package com.lin.agent.core.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantedTest {

  private val pkg = "com.lin.agent"
  private val cls = "com.lin.agent.screen.LinAccessibility"

  @Test
  fun `the full form is recognised`() {
    assertTrue(isEnabledService("com.lin.agent/com.lin.agent.screen.LinAccessibility", pkg, cls))
  }

  @Test
  fun `the relative form is recognised`() {
    // What the phone actually writes when the service is in a sub-package. A plain contains()
    // on the full class name reports "not enabled" on a phone where it is, and the symptom is
    // Lin refusing to do the thing it can do.
    assertTrue(isEnabledService("com.lin.agent/.screen.LinAccessibility", pkg, cls))
  }

  @Test
  fun `the bare class name is recognised`() {
    assertTrue(isEnabledService("com.lin.agent/LinAccessibility", pkg, cls))
    assertTrue(isEnabledService("com.lin.agent/.LinAccessibility", pkg, cls))
  }

  @Test
  fun `one of several enabled services is found`() {
    val list =
      "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService:" +
        "com.lin.agent/.screen.LinAccessibility:" +
        "com.example.other/.Service"
    assertTrue(isEnabledService(list, pkg, cls))
  }

  @Test
  fun `another app's service is not ours`() {
    assertFalse(isEnabledService("com.example.other/.screen.LinAccessibility", pkg, cls))
  }

  @Test
  fun `our package with somebody else's class is not ours`() {
    assertFalse(isEnabledService("com.lin.agent/.screen.SomethingElse", pkg, cls))
  }

  @Test
  fun `nothing enabled is not enabled`() {
    assertFalse(isEnabledService(null, pkg, cls))
    assertFalse(isEnabledService("", pkg, cls))
    assertFalse(isEnabledService("   ", pkg, cls))
    assertFalse(isEnabledService("::", pkg, cls))
  }

  @Test
  fun `an entry with no slash does not match`() {
    assertFalse(isEnabledService("com.lin.agent", pkg, cls))
  }

  @Test
  fun `a prefix of our package is not our package`() {
    // "com.lin.agentx" starts with "com.lin.agent", and a contains() or startsWith() on the
    // package would take it for ours.
    assertFalse(isEnabledService("com.lin.agentx/.screen.LinAccessibility", pkg, cls))
  }
}
