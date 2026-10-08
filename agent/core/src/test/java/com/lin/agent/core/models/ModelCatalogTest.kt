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
   * The rule that cost the most to learn: a chip name never changes which file is fetched.
   *
   * It used to. `fileFor(socModel)` returned the NPU build when the Hub had one for the chip, so
   * on an SM8750 - this phone - the downloader fetched the 3.02 GB NPU build and then checked it
   * against the 2.59 GB portable build's length, because the size was a separate field that did
   * not vary. Every attempt failed its own verification, and because a truncated body is a
   * retryable failure, it did it five times: about fifteen gigabytes of mobile data for no model.
   *
   * So this asserts the absence of the behaviour an earlier test asserted the presence of.
   */
  @Test
  fun noChipChangesWhichFileIsDownloaded() {
    // Every chip the catalogue names a build for, plus the two values a real device can report
    // that are not in it at all.
    val chips = ModelCatalog.all.flatMap { it.socBuilds.keys } + listOf("", "something-unreleased")
    for (model in ModelCatalog.all) {
      for (chip in chips) {
        assertEquals(
          "${model.name} must not vary its build on $chip",
          model.portable,
          model.buildFor(chip),
        )
      }
    }
  }

  /**
   * The NPU builds are still catalogued, with their own sizes.
   *
   * Carried rather than dropped: the mapping is correct, the chip in this phone is in it, and
   * what is missing is a loader - not data. Each one carries its own length so that enabling it
   * cannot reintroduce the mismatch above.
   */
  @Test
  fun npuBuildsAreCataloguedWithTheirOwnSizes() {
    val embedder = ModelCatalog.EMBEDDING_GEMMA_2.socBuilds.getValue("SM8750")
    assertEquals("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm", embedder.file)
    assertEquals(543_522_816L, embedder.sizeBytes)

    val chat = ModelCatalog.GEMMA_4_E2B.socBuilds.getValue("SM8750")
    assertEquals("gemma-4-E2B-it_qualcomm_sm8750.litertlm", chat.file)
    assertEquals(3_016_294_400L, chat.sizeBytes)
  }

  /**
   * No SoC build shares the portable build's length.
   *
   * This is the invariant that makes the old bug unmissable rather than subtle. If any of these
   * happened to match, the mismatch would have gone unnoticed on that chip - and the next person
   * to look would have found a catalogue that seemed fine.
   */
  @Test
  fun noNpuBuildHasThePortableBuildsLength() {
    for (model in ModelCatalog.all) {
      for ((soc, build) in model.socBuilds) {
        assertTrue(
          "${model.name} on $soc is ${build.sizeBytes}, same as the portable build - " +
            "a size mismatch would be invisible on that chip",
          build.sizeBytes != model.portable.sizeBytes,
        )
      }
    }
  }

  /**
   * E2B has NPU builds for four chips; E4B has none.
   *
   * An earlier version of this test asserted that no chat model had SoC builds, which was wrong -
   * it was describing the catalogue rather than the repositories. Listing them showed E2B carries
   * four and E4B carries none, so there is no uniform rule to assert, and the naming convention
   * differs from the embedder's as well.
   */
  @Test
  fun e2bHasNpuBuildsAndE4bHasNone() {
    assertEquals(4, ModelCatalog.GEMMA_4_E2B.socBuilds.size)
    assertTrue(ModelCatalog.GEMMA_4_E4B.socBuilds.isEmpty())
  }

  /** Every SoC build named must belong to the model that names it, not another one's. */
  @Test
  fun socBuildNamesLookLikeTheirModel() {
    for (model in ModelCatalog.all) {
      for ((soc, build) in model.socBuilds) {
        assertTrue(
          "${build.file} does not look like a build of ${model.name}",
          build.file.startsWith(model.portable.file.removeSuffix(".litertlm")),
        )
        assertTrue(
          "${build.file} should name its chip",
          build.file.lowercase().contains(soc.lowercase().replace(" ", "_")),
        )
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
