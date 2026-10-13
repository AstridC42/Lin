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

package com.lin.agent.core.training

import com.lin.agent.core.training.TrainingTurn.Role
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recording conversations, and packing them in thirties. */
class TrainingArchiveTest {

  @get:Rule val folder = TemporaryFolder()

  private lateinit var archive: TrainingArchive

  @Before
  fun setUp() {
    archive = TrainingArchive(folder.root)
  }

  private fun exchange(n: Int) =
    listOf(TrainingTurn(Role.USER, "Question $n?"), TrainingTurn(Role.ASSISTANT, "Answer $n."))

  private fun recordMany(count: Int, from: Int = 1) {
    for (i in from until from + count) archive.record("conversation-$i", exchange(i))
  }

  // ---- recording ----

  @Test
  fun aConversationIsRecordedOnce() {
    archive.record("abc", exchange(1))
    assertEquals(1, archive.state(currentId = null).pending)
  }

  /**
   * A conversation that continues overwrites its own record.
   *
   * Without this, every return to a conversation would add another partial copy of it, and
   * thirty "conversations" could be thirty prefixes of one.
   */
  @Test
  fun recordingTheSameConversationAgainReplacesItRatherThanAddingToTheCount() {
    archive.record("abc", exchange(1))
    archive.record("abc", exchange(1) + exchange(2))
    assertEquals(1, archive.state(currentId = null).pending)
  }

  @Test
  fun nothingWorthTrainingOnIsNotRecordedAtAll() {
    archive.record("abc", listOf(TrainingTurn(Role.USER, "Hello?")))
    assertEquals(0, archive.state(currentId = null).pending)
  }

  /** The conversation still being had may grow, so it is not counted as ready. */
  @Test
  fun theCurrentConversationIsNotCountedAsPending() {
    archive.record("current", exchange(1))
    archive.record("finished", exchange(2))
    assertEquals(1, archive.state(currentId = "current").pending)
  }

  // ---- packing ----

  @Test
  fun nothingIsPackedBeforeThereAreThirty() {
    recordMany(TrainingData.PACK_SIZE - 1)
    assertNull(archive.packIfReady(currentId = null))
  }

  @Test
  fun thirtyConversationsBecomeOneFileOfThirtyLines() {
    recordMany(TrainingData.PACK_SIZE)
    val pack = archive.packIfReady(currentId = null)
    assertNotNull(pack)
    assertEquals(TrainingData.PACK_SIZE, pack!!.conversations)
    assertEquals(TrainingData.PACK_SIZE, pack.file.readLines().count { it.isNotBlank() })
  }

  @Test
  fun packedConversationsAreNoLongerPending() {
    recordMany(TrainingData.PACK_SIZE)
    archive.packIfReady(currentId = null)
    assertEquals(0, archive.state(currentId = null).pending)
  }

  /** Extra conversations wait for the next pack rather than being lost or packed early. */
  @Test
  fun moreThanThirtyLeavesTheRemainderForNextTime() {
    recordMany(TrainingData.PACK_SIZE + 5)
    val pack = archive.packIfReady(currentId = null)
    assertEquals(TrainingData.PACK_SIZE, pack!!.conversations)
    assertEquals(5, archive.state(currentId = null).pending)
  }

  @Test
  fun aSecondPackGetsTheNextNumber() {
    recordMany(TrainingData.PACK_SIZE)
    val first = archive.packIfReady(currentId = null)!!
    recordMany(TrainingData.PACK_SIZE, from = 100)
    val second = archive.packIfReady(currentId = null)!!
    assertEquals(1, TrainingData.sequenceOf(first.name))
    assertEquals(2, TrainingData.sequenceOf(second.name))
  }

  /** A pack taken off the phone and deleted must not have its number reused. */
  @Test
  fun aNumberIsNotReusedAfterAPackIsDeleted() {
    recordMany(TrainingData.PACK_SIZE)
    val first = archive.packIfReady(currentId = null)!!
    recordMany(TrainingData.PACK_SIZE, from = 100)
    val second = archive.packIfReady(currentId = null)!!
    archive.delete(second)

    recordMany(TrainingData.PACK_SIZE, from = 200)
    val third = archive.packIfReady(currentId = null)!!
    assertEquals(1, TrainingData.sequenceOf(first.name))
    assertEquals(3, TrainingData.sequenceOf(third.name))
  }

  @Test
  fun aPackIsOneConversationPerLineAndEachLineLoadsOnItsOwn() {
    recordMany(TrainingData.PACK_SIZE)
    val pack = archive.packIfReady(currentId = null)!!
    for (line in pack.file.readLines().filter { it.isNotBlank() }) {
      val messages = org.json.JSONObject(line).getJSONArray("messages")
      assertEquals(2, messages.length())
    }
  }

  // ---- listing and clearing ----

  @Test
  fun packsAreListedNewestFirst() {
    recordMany(TrainingData.PACK_SIZE)
    archive.packIfReady(currentId = null)
    recordMany(TrainingData.PACK_SIZE, from = 100)
    archive.packIfReady(currentId = null)
    val names = archive.packs().map { it.name }
    assertEquals(2, names.size)
    assertEquals(2, TrainingData.sequenceOf(names.first()))
  }

  @Test
  fun clearingEverythingLeavesNothingBehind() {
    recordMany(TrainingData.PACK_SIZE + 3)
    archive.packIfReady(currentId = null)
    archive.deleteEverything()
    val state = archive.state(currentId = null)
    assertEquals(0, state.pending)
    assertTrue(state.packs.isEmpty())
    assertEquals(0L, state.totalBytes)
  }

  /**
   * Clearing the phone must not start the numbering again.
   *
   * Packs 1 to 5 may already be on somebody's computer; new ones called the same thing would
   * overwrite them as soon as they were copied into the same folder.
   */
  @Test
  fun numberingSurvivesClearingEverything() {
    recordMany(TrainingData.PACK_SIZE)
    archive.packIfReady(currentId = null)
    archive.deleteEverything()

    recordMany(TrainingData.PACK_SIZE, from = 100)
    val next = archive.packIfReady(currentId = null)!!
    assertEquals(2, TrainingData.sequenceOf(next.name))
  }

  @Test
  fun theCountdownToTheNextPackIsWhatIsLeft() {
    recordMany(4)
    assertEquals(TrainingData.PACK_SIZE - 4, archive.state(currentId = null).untilNextPack)
  }

  @Test
  fun somethingElseInTheDirectoryIsNotListedAsAPack() {
    recordMany(TrainingData.PACK_SIZE)
    archive.packIfReady(currentId = null)
    File(archive.packDirectory(), "notes.txt").writeText("not a pack")
    assertEquals(1, archive.packs().size)
    assertFalse(archive.packs().any { it.name == "notes.txt" })
  }
}
