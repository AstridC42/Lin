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

class PermissionsTest {

  @Test
  fun `the weather tool needs a rough location`() {
    assertEquals(COARSE_LOCATION, permissionNeededBy("weather"))
  }

  @Test
  fun `a tool that needs nothing asks for nothing`() {
    assertNull(permissionNeededBy("clock"))
    assertNull(permissionNeededBy(""))
    // Not fine rather than coarse, and not here at all: a forecast is the same across a town.
    assertNull(permissionNeededBy("android.permission.ACCESS_FINE_LOCATION"))
  }

  @Test
  fun `the permission is the one the manifest declares`() {
    // Spelled out rather than taken from android.Manifest, so a typo here would be a typo the
    // phone silently ignores - checkSelfPermission on a name that does not exist is simply
    // denied, which looks exactly like the user refusing.
    assertEquals("android.permission.ACCESS_COARSE_LOCATION", COARSE_LOCATION)
  }
}
