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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether an app which will not open offers a way back in.
 *
 * Both directions fail differently and both matter. Too reluctant, and somebody whose app dies
 * before the first frame gets nothing at all - no report, no escape, and no way to learn more from
 * a phone with no computer attached. Too eager, and somebody who changed their mind about opening
 * the app and swiped it away is dropped into a recovery screen.
 */
class LaunchHealthTest {

  @Test
  fun aFirstLaunchIsNotRecovery() {
    val d = LaunchHealth.decide(launchInProgress = false, recordedFailures = 0)
    assertEquals(0, d.failures)
    assertFalse(d.recover)
  }

  /** One failure is also what force-quitting during start-up looks like. */
  @Test
  fun oneFailureCountsButDoesNotTriggerRecovery() {
    val d = LaunchHealth.decide(launchInProgress = true, recordedFailures = 0)
    assertEquals(1, d.failures)
    assertFalse("one failure must not be enough to act on", d.recover)
  }

  @Test
  fun twoFailuresInARowTriggerRecovery() {
    val d = LaunchHealth.decide(launchInProgress = true, recordedFailures = 1)
    assertEquals(2, d.failures)
    assertTrue(d.recover)
  }

  @Test
  fun recoveryStaysOnWhileLaunchesKeepFailing() {
    for (recorded in 1..20) {
      val d = LaunchHealth.decide(launchInProgress = true, recordedFailures = recorded)
      assertTrue("$recorded recorded failures should still recover", d.recover)
      assertEquals(recorded + 1, d.failures)
    }
  }

  /** One good launch clears it, whatever the count had reached. This is the path out. */
  @Test
  fun aSuccessfulLaunchClearsALongRunOfFailures() {
    val d = LaunchHealth.decide(launchInProgress = false, recordedFailures = 17)
    assertEquals(0, d.failures)
    assertFalse(d.recover)
  }

  /**
   * The count is read from a disk that may be the thing misbehaving, so a negative value must not
   * be able to subtract its way out of the screen that exists for a misbehaving disk.
   */
  @Test
  fun anImpossibleStoredCountStillCountsThisFailure() {
    val d = LaunchHealth.decide(launchInProgress = true, recordedFailures = -5)
    assertEquals(1, d.failures)
    assertFalse(d.recover)
    assertTrue(LaunchHealth.decide(launchInProgress = true, recordedFailures = 1).recover)
  }
}
