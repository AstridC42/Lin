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
import org.junit.Test

/**
 * Where a heard question goes.
 *
 * The bug this exists for is silent: a background app's activity launch is dropped rather than
 * refused, so a wake word without the overlay permission heard everything and showed nothing.
 */
class WakeRouteTest {

  @Test
  fun `permission to appear over other apps means the panel`() {
    assertEquals(
      WakeRoute.PANEL,
      wakeRoute(canAppearOverOtherApps = true, appHasVisibleWindow = false),
    )
  }

  @Test
  fun `Lin already on screen means the panel, with no permission at all`() {
    // An app with a window may start another. Worth having: this is the case somebody hits
    // first, while they are looking at Lin and trying the wake word out.
    assertEquals(
      WakeRoute.PANEL,
      wakeRoute(canAppearOverOtherApps = false, appHasVisibleWindow = true),
    )
  }

  @Test
  fun `neither means a notification rather than nothing`() {
    assertEquals(
      WakeRoute.NOTIFICATION,
      wakeRoute(canAppearOverOtherApps = false, appHasVisibleWindow = false),
    )
  }

  @Test
  fun `both is still the panel`() {
    assertEquals(
      WakeRoute.PANEL,
      wakeRoute(canAppearOverOtherApps = true, appHasVisibleWindow = true),
    )
  }
}
