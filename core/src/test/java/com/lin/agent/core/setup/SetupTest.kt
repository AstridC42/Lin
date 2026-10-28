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

package com.lin.agent.core.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupTest {
  @Test
  fun nextSkipsWhatIsAlreadyDone() {
    assertEquals(SetupStep.BRAIN, nextStep(SetupStep.WELCOME, emptySet()))
    assertEquals(SetupStep.VOICE, nextStep(SetupStep.WELCOME, setOf(SetupStep.BRAIN, SetupStep.NOTIFICATIONS)))
    assertEquals(SetupStep.DONE, nextStep(SetupStep.WELCOME, checkedSteps.toSet()))
    assertEquals(SetupStep.BROWSER, nextStep(SetupStep.TOKEN, emptySet()))
    assertEquals(SetupStep.DONE, nextStep(SetupStep.BROWSER, emptySet()))
    assertEquals(SetupStep.DONE, nextStep(SetupStep.DONE, emptySet()))
  }

  @Test
  fun countsProgressAndEssentials() {
    assertEquals(0 to checkedSteps.size, progress(emptySet()))
    assertEquals(listOf(SetupStep.BRAIN, SetupStep.NOTIFICATIONS), missingEssentials(emptySet()))
    assertEquals(emptyList<SetupStep>(), missingEssentials(setOf(SetupStep.BRAIN, SetupStep.NOTIFICATIONS)))
  }
}
