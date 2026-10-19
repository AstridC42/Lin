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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cleaning up partial downloads that nothing will ever finish.
 *
 * Exercised against a real directory rather than a mocked one, and the rule is checked from both
 * sides: a partial nobody can reach is dead storage the app cannot even report, and a partial that
 * belongs to a download in flight must survive, because deleting it would silently restart a
 * transfer that was most of the way through.
 *
 * [ModelStore] needs a Context for its own directories, so the rule lives in a function that
 * takes one instead - which is the function this exercises, not a copy of it.
 */
class ModelStoreTest {

  @get:Rule val folder = TemporaryFolder()

  private val wantedPart =
    ModelCatalog.GEMMA_4_E2B.portable.file + ModelDownload.PART_SUFFIX

  /** The exact file the broken build left behind on an SM8750. */
  private val strayPart =
    ModelCatalog.GEMMA_4_E2B.socBuilds.getValue("SM8750").file + ModelDownload.PART_SUFFIX

  @Test
  fun aPartialForABuildThisAppWillNeverFetchIsRemoved() {
    val stray = write(strayPart, size = 4_096)
    val freed = clearStrayPartsIn(folder.root)

    assertFalse("the unreachable partial should be gone", stray.exists())
    assertEquals(4_096L, freed)
  }

  /** A download in flight writes into this one. Deleting it would throw the transfer away. */
  @Test
  fun aPartialForTheCurrentBuildIsKept() {
    val live = write(wantedPart, size = 1_000_000)
    assertEquals(0L, clearStrayPartsIn(folder.root))
    assertTrue("the live partial must survive", live.exists())
  }

  /** Finished models are not partials and are never candidates, whatever else is going on. */
  @Test
  fun completedModelsAreNeverTouched() {
    val model = write(ModelCatalog.GEMMA_4_E2B.portable.file, size = 2_048)
    val npuModel = write(ModelCatalog.EMBEDDING_GEMMA_2.socBuilds.getValue("SM8750").file, 2_048)
    write(strayPart, size = 512)

    assertEquals(512L, clearStrayPartsIn(folder.root))
    assertTrue(model.exists())
    assertTrue("an NPU model copied in by hand is still the user's file", npuModel.exists())
  }

  @Test
  fun anEmptyDirectoryFreesNothing() {
    assertEquals(0L, clearStrayPartsIn(folder.root))
  }

  /** Several at once, which is what a phone that retried the broken download looks like. */
  @Test
  fun everyUnreachablePartialGoesInOnePass() {
    write(strayPart, size = 100)
    write(ModelCatalog.EMBEDDING_GEMMA_2.socBuilds.getValue("SM8750").file + ".part", size = 200)
    write("something-else-entirely.litertlm.part", size = 300)
    val keep = write(wantedPart, size = 400)

    assertEquals(600L, clearStrayPartsIn(folder.root))
    assertTrue(keep.exists())
    assertEquals(listOf(wantedPart), folder.root.list()!!.sorted())
  }

  private fun write(name: String, size: Int): File =
    File(folder.root, name).apply { writeBytes(ByteArray(size)) }
}
