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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which downloaded model answers.
 *
 * Nothing read the user's choice before this: `chatModelName` was stored and consulted by no
 * one, so downloading a second model gave no way to use it. The app picked, and there was no
 * argument to be had.
 */
class ChatModelChoiceTest {

  private val small = ModelCatalog.chatModels.minByOrNull { it.build.sizeBytes }!!
  private val large = ModelCatalog.chatModels.maxByOrNull { it.build.sizeBytes }!!
  private val plenty = 16

  @Test
  fun whatTheUserAskedForIsWhatRuns() {
    assertEquals(large, ModelCatalog.chatModelToUse(listOf(small, large), large.name, plenty))
    assertEquals(small, ModelCatalog.chatModelToUse(listOf(small, large), small.name, plenty))
  }

  /** The old behaviour, which is still right when nobody has chosen. */
  @Test
  fun withNoChoiceThePhoneDecides() {
    val chosen = ModelCatalog.chatModelToUse(listOf(small, large), chosenName = "", memoryGb = plenty)
    assertEquals(ModelCatalog.defaultChatModel(plenty), chosen)
  }

  /**
   * A choice that is no longer downloaded falls through rather than leaving nothing.
   *
   * The setting is a preference, not a promise the file is still there - somebody who deletes
   * a model should get the other one, not an app with nothing to answer with.
   */
  @Test
  fun aChoiceThatIsNoLongerHereFallsBack() {
    assertEquals(small, ModelCatalog.chatModelToUse(listOf(small), large.name, plenty))
  }

  @Test
  fun aNameThatMatchesNothingIsIgnored() {
    assertEquals(small, ModelCatalog.chatModelToUse(listOf(small), "Something Else", plenty))
  }

  /** Deliberately fetching only the big one should get the big one, not nothing. */
  @Test
  fun theOnlyModelPresentIsUsedWhateverThePhoneWouldPrefer() {
    assertEquals(large, ModelCatalog.chatModelToUse(listOf(large), chosenName = "", memoryGb = 4))
  }

  @Test
  fun nothingDownloadedIsNothingToRun() {
    assertNull(ModelCatalog.chatModelToUse(emptyList(), large.name, plenty))
  }
}
