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

package com.google.ai.edge.gallery.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether an app that will not open offers a way back in.
 *
 * Both directions of this matter and they fail differently. Too reluctant, and somebody whose app
 * dies before the first frame gets nothing at all - no report, no escape, and no way to find out
 * more from a phone with no computer attached. Too eager, and somebody who changed their mind
 * about opening the app and swiped it away is dropped into a recovery screen offering to delete
 * their memory vault.
 */
class SafeModeTest {

  @Test
  fun aFirstLaunchIsNotSafeMode() {
    // Nothing recorded at all: no in-progress flag, no count.
    val decision = SafeMode.decide(launchInProgress = false, recordedFailures = 0)
    assertEquals(0, decision.failures)
    assertFalse(decision.safeMode)
  }

  @Test
  fun aLaunchAfterOneThatReachedTheScreenIsNotSafeMode() {
    // noteLaunchSucceeded clears both, so this is what a healthy app looks like every time.
    assertFalse(SafeMode.decide(launchInProgress = false, recordedFailures = 0).safeMode)
  }

  /** One failure is also what force-quitting during start-up looks like. */
  @Test
  fun oneFailedLaunchCountsButDoesNotTriggerSafeMode() {
    val decision = SafeMode.decide(launchInProgress = true, recordedFailures = 0)
    assertEquals(1, decision.failures)
    assertFalse("one failure must not be enough to act on", decision.safeMode)
  }

  @Test
  fun twoFailedLaunchesInARowTriggerSafeMode() {
    val decision = SafeMode.decide(launchInProgress = true, recordedFailures = 1)
    assertEquals(2, decision.failures)
    assertTrue(decision.safeMode)
  }

  /** It must not stop being safe mode just because the count kept climbing. */
  @Test
  fun safeModeStaysOnWhileLaunchesKeepFailing() {
    for (recorded in 1..20) {
      val decision = SafeMode.decide(launchInProgress = true, recordedFailures = recorded)
      assertTrue("$recorded recorded failures should still be safe mode", decision.safeMode)
      assertEquals(recorded + 1, decision.failures)
    }
  }

  /**
   * One successful launch clears it, whatever the count had reached.
   *
   * This is the path out: the recovery screen's "start normally" clears the flag, and if that
   * launch works the app is back to normal rather than permanently in recovery.
   */
  @Test
  fun aSuccessfulLaunchClearsEvenALongRunOfFailures() {
    val decision = SafeMode.decide(launchInProgress = false, recordedFailures = 17)
    assertEquals(0, decision.failures)
    assertFalse(decision.safeMode)
  }

  /**
   * The count comes off a disk that may be the thing misbehaving.
   *
   * A negative value must not be able to subtract its way out of safe mode - that would be the
   * failing disk keeping the user out of the screen that exists for a failing disk.
   */
  @Test
  fun anImpossibleStoredCountStillCountsThisFailure() {
    val decision = SafeMode.decide(launchInProgress = true, recordedFailures = -5)
    assertEquals(1, decision.failures)
    assertFalse(decision.safeMode)

    // And the launch after it reaches safe mode normally, rather than needing five more.
    assertTrue(SafeMode.decide(launchInProgress = true, recordedFailures = 1).safeMode)
  }
}
