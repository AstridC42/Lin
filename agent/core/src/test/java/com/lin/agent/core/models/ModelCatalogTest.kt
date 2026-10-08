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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

  /**
   * On this phone's 12 GB the default is E2B, not E4B.
   *
   * E4B's stated minimum is exactly 12, so a naive "does it fit" check picks it - and 3.66 GB of
   * weights plus an 8,192-token cache plus the system is where Android starts killing processes.
   * An OOM kill leaves no stack trace, so that failure looks like the app vanishing for no reason,
   * which is a bad thing to choose by default.
   */
  @Test
  fun twelveGigabytesDefaultsToTheSmallerModel() {
    val chosen = ModelCatalog.defaultChatModel(memoryGb = 12)
    assertEquals(ModelCatalog.GEMMA_4_E2B, chosen)
  }

  @Test
  fun sixteenGigabytesStillDefaultsToTheSmallestThatFits() {
    // Deliberate: the default is the safe one, and E4B is a setting the user opts into rather
    // than something that appears because the phone happens to be large.
    assertEquals(ModelCatalog.GEMMA_4_E2B, ModelCatalog.defaultChatModel(memoryGb = 16))
  }

  @Test
  fun aPhoneTooSmallForAnythingGetsNoDefaultRatherThanABadOne() {
    assertNull(ModelCatalog.defaultChatModel(memoryGb = 4))
  }

  @Test
  fun comfortMeansRoomAboveTheMinimumNotEqualToIt() {
    assertFalse(
      "12 GB is E4B's minimum, which is not the same as comfortable",
      ModelCatalog.isComfortable(ModelCatalog.GEMMA_4_E4B, memoryGb = 12),
    )
    assertTrue(ModelCatalog.isComfortable(ModelCatalog.GEMMA_4_E4B, memoryGb = 16))
    assertTrue(ModelCatalog.isComfortable(ModelCatalog.GEMMA_4_E2B, memoryGb = 12))
  }

  /**
   * The SoC mapping, which exists before anything uses it.
   *
   * This phone is an SM8750, and an NPU build for exactly that chip is published. Nothing selects
   * it yet - that needs vendor runtime libraries on the device - but the mapping being correct now
   * is what makes enabling it later a data change.
   */
  @Test
  fun theEmbedderHasAnNpuBuildForThisPhonesChip() {
    val file = ModelCatalog.EMBEDDING_GEMMA_2.fileFor(socModel = "SM8750")
    assertEquals("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm", file)
  }

  @Test
  fun anUnknownChipFallsBackToTheGenericBuild() {
    assertEquals(
      "embeddinggemma-2-740m.litertlm",
      ModelCatalog.EMBEDDING_GEMMA_2.fileFor(socModel = "something-unreleased"),
    )
    // And an empty SoC, which is what is reported below API 31.
    assertEquals(
      "embeddinggemma-2-740m.litertlm",
      ModelCatalog.EMBEDDING_GEMMA_2.fileFor(socModel = ""),
    )
  }

  /**
   * E2B has an SoC build for this phone's chip; E4B has none.
   *
   * An earlier version of this test asserted that no chat model had SoC builds, which was wrong -
   * it was describing the catalogue rather than the repositories. Listing them showed E2B carries
   * four and E4B carries none, so there is no uniform rule to assert, and the naming convention
   * differs from the embedder's as well.
   */
  @Test
  fun e2bHasAnSocBuildForThisPhoneAndE4bHasNone() {
    assertEquals(
      "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
      ModelCatalog.GEMMA_4_E2B.fileFor(socModel = "SM8750"),
    )
    assertTrue(ModelCatalog.GEMMA_4_E4B.socFiles.isEmpty())
    assertEquals(
      ModelCatalog.GEMMA_4_E4B.file,
      ModelCatalog.GEMMA_4_E4B.fileFor(socModel = "SM8750"),
    )
  }

  /** Every SoC build named must belong to the model that names it, not another one's. */
  @Test
  fun socBuildNamesLookLikeTheirModel() {
    for (model in ModelCatalog.all) {
      for ((soc, file) in model.socFiles) {
        assertTrue(
          "$file does not look like a build of ${model.name}",
          file.startsWith(model.file.removeSuffix(".litertlm")),
        )
        assertTrue("$file should name its chip", file.lowercase().contains(soc.lowercase().replace(" ", "_")))
      }
    }
  }

  @Test
  fun everyModelIsNamedUniquelyAndFindable() {
    val names = ModelCatalog.all.map { it.name }
    assertEquals(names.size, names.toSet().size)
    for (name in names) assertEquals(name, ModelCatalog.byName(name)?.name)
    assertNull(ModelCatalog.byName("not a model"))
  }
}
