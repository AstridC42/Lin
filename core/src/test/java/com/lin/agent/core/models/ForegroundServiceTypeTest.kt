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

package com.lin.agent.core.models

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides whether a download is allowed to ask to run in the foreground.
 *
 * This exists because getting it wrong did not produce a failed download - it closed the app, from
 * inside WorkManager's service, on the first press of Download, and left the button dead
 * afterwards. The test is here so the next person to add a service type has to think about the
 * manifest at the same time.
 */
class ForegroundServiceTypeTest {

  private val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
  private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

  /** The ordinary case, once the manifest declares what this app's workers actually do. */
  @Test
  fun aDeclaredTypeIsTheTypeToAskFor() {
    assertEquals(dataSync, foregroundTypeFor(declaredInManifest = dataSync, wanted = dataSync))
  }

  /**
   * Nothing declared is the bug that shipped: WorkManager's service carries no type of its own,
   * so without an entry in the app's manifest this reads as zero.
   */
  @Test
  fun nothingDeclaredMeansDoNotAsk() {
    assertNull(foregroundTypeFor(declaredInManifest = 0, wanted = dataSync))
  }

  /** A manifest that declares something else is no better than one that declares nothing. */
  @Test
  fun aDifferentDeclaredTypeMeansDoNotAsk() {
    assertNull(foregroundTypeFor(declaredInManifest = location, wanted = dataSync))
  }

  /** Declaring more than this worker needs is fine; it asks for its own slice. */
  @Test
  fun aWiderDeclarationNarrowsToWhatIsWanted() {
    assertEquals(
      dataSync,
      foregroundTypeFor(declaredInManifest = dataSync or location, wanted = dataSync),
    )
  }

  /**
   * Asking for nothing is never the fallback.
   *
   * Worth a test of its own, because "pass zero and let the platform work it out" is the obvious
   * thing to reach for and it is the same crash: on API 34+ going foreground with no type throws
   * MissingForegroundServiceTypeException, which WorkManager does not catch either.
   */
  @Test
  fun wantingNothingIsNotAnAnswer() {
    assertNull(foregroundTypeFor(declaredInManifest = dataSync, wanted = 0))
    assertNull(foregroundTypeFor(declaredInManifest = 0, wanted = 0))
  }
}
