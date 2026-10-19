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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard against a tool reopening another app in a loop.
 *
 * Written for a loop somebody actually hit: the calendar opens, they cancel, and it opens
 * again, because the tool result honestly says the event is not saved and a small model reads
 * that as "did not work".
 */
class LaunchGuardTest {

  @Test
  fun theFirstLaunchIsAllowed() {
    assertTrue(LaunchGuard().allow("calendar", now = 1_000))
  }

  /** The loop: the same tool, seconds later, because the model did not believe the result. */
  @Test
  fun thesameToolAgainSecondsLaterIsRefused() {
    val guard = LaunchGuard()
    assertTrue(guard.allow("calendar", now = 1_000))
    assertFalse(guard.allow("calendar", now = 2_000))
    assertFalse(guard.allow("calendar", now = 15_000))
  }

  /** Somebody who looked, cancelled and asked for a different time takes longer than this. */
  @Test
  fun thesameToolLongAfterIsAllowedAgain() {
    val guard = LaunchGuard()
    assertTrue(guard.allow("calendar", now = 1_000))
    assertTrue(guard.allow("calendar", now = 1_000 + 20_000))
  }

  /** One tool being held back must not hold back another. */
  @Test
  fun adifferentToolIsNotHeldBack() {
    val guard = LaunchGuard()
    assertTrue(guard.allow("calendar", now = 1_000))
    assertTrue(guard.allow("alarm", now = 1_100))
  }

  /** A user who plainly asks again should not be told to wait. */
  @Test
  fun forgettingLetsItThroughImmediately() {
    val guard = LaunchGuard()
    assertTrue(guard.allow("calendar", now = 1_000))
    guard.forget("calendar")
    assertTrue(guard.allow("calendar", now = 1_100))
  }

  /**
   * A clock that goes backwards must not lock the tool out.
   *
   * It happens: a time-zone change, a manual correction, an NTP step. Refusing for ever
   * afterwards would be a worse bug than the one this prevents.
   */
  @Test
  fun aClockThatGoesBackwardsDoesNotLockItOut() {
    val guard = LaunchGuard()
    assertTrue(guard.allow("calendar", now = 1_000_000))
    assertTrue(guard.allow("calendar", now = 5_000))
  }
}
