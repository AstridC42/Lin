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

package com.lin.agent.core.backup

import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemorySource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only copy of things that cannot be regenerated.
 *
 * A memory came out of a conversation that is gone; there is nowhere else to get it from. So
 * the two failures that matter here are a backup that cannot be read back, and a restore that
 * quietly drops part of what was in the file - both of which look like success.
 */
class BackupFormatTest {

  private fun stored(text: String, source: MemorySource = MemorySource.TOLD) =
    StoredMemory(
      Memory(id = 7, text = text, source = source, reference = "ref", createdAt = 1_760_000_000_000L),
      floatArrayOf(0.5f, -0.25f, 0.125f),
    )

  // ---- the round trip ----

  @Test
  fun aMemorySurvivesBeingWrittenAndReadBack() {
    val original = listOf(stored("Otto's sister is Mia."), stored("A photo of a dog", MemorySource.PHOTO))
    val back = BackupFormat.readMemories(BackupFormat.memoriesJson(original))

    assertEquals(2, back.size)
    assertEquals("Otto's sister is Mia.", back[0].memory.text)
    assertEquals(MemorySource.PHOTO, back[1].memory.source)
    assertEquals("ref", back[0].memory.reference)
    assertEquals(1_760_000_000_000L, back[0].memory.createdAt)
  }

  /** Without the vector a restored memory cannot be found by meaning until it is re-embedded. */
  @Test
  fun theVectorSurvivesExactly() {
    val back = BackupFormat.readMemories(BackupFormat.memoriesJson(listOf(stored("x"))))
    assertTrue(floatArrayOf(0.5f, -0.25f, 0.125f).contentEquals(back.single().vector))
  }

  @Test
  fun awkwardTextSurvives() {
    val text = "She said \"no\",\nthen left — 100% sure."
    val back = BackupFormat.readMemories(BackupFormat.memoriesJson(listOf(stored(text))))
    assertEquals(text, back.single().memory.text)
  }

  /** Ids belong to the database a memory came out of; carrying them would collide. */
  @Test
  fun theIdIsNotCarriedAcross() {
    val back = BackupFormat.readMemories(BackupFormat.memoriesJson(listOf(stored("x"))))
    assertEquals(0L, back.single().memory.id)
  }

  // ---- reading something that is not a backup ----

  @Test
  fun anUnreadableFileYieldsNothingRatherThanAPartialList() {
    assertTrue(BackupFormat.readMemories("not json").isEmpty())
    assertTrue(BackupFormat.readMemories("{}").isEmpty())
    assertTrue(BackupFormat.readMemories("").isEmpty())
  }

  @Test
  fun aRowWithNoTextIsDroppedRatherThanRestoredEmpty() {
    val json = """{"memories":[{"text":"  ","vector":[]},{"text":"Real.","vector":[]}]}"""
    assertEquals(listOf("Real."), BackupFormat.readMemories(json).map { it.memory.text })
  }

  // ---- the manifest ----

  @Test
  fun aManifestRoundTrips() {
    val json = BackupFormat.manifestJson("0.1.0-dev.18", 1_760_000_000_000L, memories = 12, packs = 3)
    val manifest = BackupFormat.readManifest(json)!!
    assertEquals(BackupFormat.FORMAT, manifest.format)
    assertEquals("0.1.0-dev.18", manifest.appVersion)
    assertEquals(12, manifest.memories)
    assertEquals(3, manifest.packs)
  }

  /**
   * A newer backup is refused rather than half-read.
   *
   * Reading what this build understands and silently dropping the rest, from the only copy of
   * somebody's memories, is worse than saying it cannot be read.
   */
  @Test
  fun aBackupFromALaterBuildIsRefused() {
    val future = JSONObject().put("format", BackupFormat.FORMAT + 1).toString()
    assertNull(BackupFormat.readManifest(future))
  }

  @Test
  fun somethingThatIsNotABackupIsRefused() {
    assertNull(BackupFormat.readManifest("not json"))
    assertNull(BackupFormat.readManifest("{}"))
    assertNull(BackupFormat.readManifest(JSONObject().put("format", 0).toString()))
  }

  // ---- restoring onto an app that is not empty ----

  /**
   * Restoring never deletes.
   *
   * The common case is an empty app, where it makes no difference. The dangerous case is a
   * populated one, where replacing would throw away everything learnt since the backup.
   */
  @Test
  fun whatIsAlreadyHeldIsNotAddedTwice() {
    val held = listOf(Memory(text = "Otto's sister is Mia.", source = MemorySource.TOLD))
    val fromFile = listOf(stored("Otto's sister is Mia."), stored("Otto prefers trains."))
    val toAdd = BackupFormat.notAlreadyHeld(fromFile, held)
    assertEquals(listOf("Otto prefers trains."), toAdd.map { it.memory.text })
  }

  @Test
  fun theSameFactWrittenTwiceInTheFileIsAddedOnce() {
    val fromFile = listOf(stored("Otto prefers trains."), stored("  otto prefers trains.  "))
    assertEquals(1, BackupFormat.notAlreadyHeld(fromFile, emptyList()).size)
  }

  @Test
  fun restoringIntoAnEmptyAppBringsEverything() {
    val fromFile = listOf(stored("One."), stored("Two."))
    assertEquals(2, BackupFormat.notAlreadyHeld(fromFile, emptyList()).size)
  }

  // ---- the file itself ----

  @Test
  fun backupsSortByWhenTheyWereMade() {
    val names =
      listOf(1_760_000_000_000L, 1_760_086_400_000L, 1_770_000_000_000L).map { BackupFormat.fileName(it) }
    assertEquals(names, names.sorted())
    assertTrue(names.all { it.startsWith("lin-backup-") && it.endsWith(".zip") })
  }
}
