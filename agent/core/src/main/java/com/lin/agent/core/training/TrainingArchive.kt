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

import android.util.Log
import java.io.File

/** A finished pack, ready to be taken off the phone. */
data class TrainingPack(val file: File, val conversations: Int, val sizeBytes: Long) {
  val name: String
    get() = file.name
}

/** What the archive currently holds. */
data class ArchiveState(
  /** Conversations recorded but not yet packed. */
  val pending: Int,
  val packs: List<TrainingPack>,
) {
  val totalBytes: Long
    get() = packs.sumOf { it.sizeBytes }

  /** How many more conversations until the next pack. */
  val untilNextPack: Int
    get() = (TrainingData.PACK_SIZE - pending).coerceAtLeast(0)
}

/**
 * Keeps a copy of every conversation, and packs them in thirties.
 *
 * This is the one part of the app that keeps transcripts, and it does so because it was asked
 * for: the corpus is the point. Worth being plain about what that means rather than burying it -
 * everything said to Lin is written to the phone's storage in readable text, and a conversation
 * that used a tool carries whatever the tool returned, which can include other people's
 * messages and the contents of documents. The screen says so, there is a switch, and nothing is
 * deleted without being asked.
 *
 * Takes the directory to work in rather than a `Context`, which is what makes all of this
 * testable without a device - the same reason [com.lin.agent.core.models.ModelStore] does.
 *
 * One file per conversation while it is open, named by its id, so a conversation that continues
 * overwrites its own record rather than producing a second partial copy of itself. Only closed
 * conversations - anything that is not the current one - count towards a pack, because a record
 * that can still grow would otherwise be packed half-finished.
 */
class TrainingArchive(root: File) {

  private val staging = File(root, STAGING)
  private val packed = File(root, PACKS)

  /** Writes or replaces the record for one conversation. */
  fun record(conversationId: String, turns: List<TrainingTurn>) {
    val line = TrainingData.asJsonl(turns) ?: return
    runCatching {
        staging.mkdirs()
        File(staging, "$conversationId.jsonl").writeText(line)
      }
      .onFailure { Log.w(TAG, "Could not record a conversation", it) }
  }

  /**
   * Packs the closed conversations if there are enough of them, returning the pack if made.
   *
   * [currentId] is left out of the count and out of the pack: it may still grow.
   */
  fun packIfReady(currentId: String?): TrainingPack? {
    val closed = closedRecords(currentId)
    if (closed.size < TrainingData.PACK_SIZE) return null

    val taking = closed.sortedBy { it.lastModified() }.take(TrainingData.PACK_SIZE)
    val lines = taking.mapNotNull { file -> runCatching { file.readText().trim() }.getOrNull() }
    if (lines.isEmpty()) return null

    return runCatching {
        packed.mkdirs()
        val target = File(packed, TrainingData.packName(nextSequence(), System.currentTimeMillis()))
        target.writeText(lines.joinToString("\n", postfix = "\n"))
        // Only once the pack is safely written. A crash in between leaves the records staged
        // and the pack remade next time, which is better than losing the conversations.
        taking.forEach { it.delete() }
        TrainingPack(target, conversations = lines.size, sizeBytes = target.length())
      }
      .onFailure { Log.w(TAG, "Could not write a pack", it) }
      .getOrNull()
  }

  fun state(currentId: String?): ArchiveState =
    ArchiveState(pending = closedRecords(currentId).size, packs = packs())

  fun packs(): List<TrainingPack> =
    runCatching {
        packed
          .listFiles { file -> TrainingData.sequenceOf(file.name) != null }
          .orEmpty()
          .map { TrainingPack(it, conversations = countLines(it), sizeBytes = it.length()) }
          .sortedByDescending { it.name }
      }
      .onFailure { Log.w(TAG, "Could not list packs", it) }
      .getOrDefault(emptyList())

  fun delete(pack: TrainingPack) {
    runCatching { pack.file.delete() }.onFailure { Log.w(TAG, "Could not delete a pack", it) }
  }

  /**
   * Throws away everything: the packs and the conversations waiting to be packed.
   *
   * The current conversation's record goes too. Somebody clearing this wants it gone, not gone
   * except for the one they are in the middle of.
   */
  fun deleteEverything() {
    runCatching {
        staging.listFiles()?.forEach { it.delete() }
        // Everything except the counter. Packs 1 to 5 may already be sitting on somebody's
        // computer, and restarting the numbering here would have the next ones overwrite them
        // the moment they were copied into the same folder.
        packed.listFiles()?.forEach { if (it.name != SEQUENCE) it.delete() }
      }
      .onFailure { Log.w(TAG, "Could not clear the archive", it) }
  }

  /** Where packs live, for whatever takes them off the phone later. */
  fun packDirectory(): File = packed.also { if (!it.exists()) it.mkdirs() }

  private fun closedRecords(currentId: String?): List<File> =
    runCatching {
        staging
          .listFiles { file -> file.name.endsWith(".jsonl") }
          .orEmpty()
          .filter { it.nameWithoutExtension != currentId }
      }
      .getOrDefault(emptyList())

  /**
   * The next pack number, remembered rather than worked out from what is on disk.
   *
   * Derived from the files present, this reused a number as soon as one was deleted - and
   * deleting is the normal thing to do, because the whole point is to take the packs off the
   * phone. Two different packs called `lin-0002-…` would then collide in whatever folder they
   * were copied into: one silently overwriting the other, thirty conversations lost from a
   * corpus nobody was counting the lines of.
   *
   * The files on disk are still consulted as a floor, so a lost counter cannot start the
   * numbering again from one while earlier packs are still here.
   */
  private fun nextSequence(): Int {
    val counter = File(packed, SEQUENCE)
    val remembered = runCatching { counter.readText().trim().toInt() }.getOrDefault(0)
    val onDisk = packs().mapNotNull { TrainingData.sequenceOf(it.name) }.maxOrNull() ?: 0
    val next = maxOf(remembered, onDisk) + 1
    runCatching { counter.writeText(next.toString()) }
      .onFailure { Log.w(TAG, "Could not save the pack counter", it) }
    return next
  }

  private fun countLines(file: File): Int =
    runCatching { file.readLines().count { it.isNotBlank() } }.getOrDefault(0)

  private companion object {
    const val TAG = "LinTrainingArchive"
    const val STAGING = "training-staging"
    const val PACKS = "training-packs"

    /** The highest pack number used so far. Not a pack, so it is never listed as one. */
    const val SEQUENCE = ".sequence"
  }
}
