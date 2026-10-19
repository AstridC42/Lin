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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** A memory and the vector that finds it. */
data class StoredMemory(val memory: Memory, val vector: FloatArray) {
  // FloatArray has identity equals, which would make every comparison in a test wrong.
  override fun equals(other: Any?): Boolean =
    other is StoredMemory && memory == other.memory && vector.contentEquals(other.vector)

  override fun hashCode(): Int = 31 * memory.hashCode() + vector.contentHashCode()
}

/** What a backup says about itself. */
data class Manifest(
  val format: Int,
  val appVersion: String,
  val createdAt: Long,
  val memories: Int,
  val packs: Int,
)

/**
 * The shape of a backup, and the rules for reading one back.
 *
 * A backup is the only copy of the irreplaceable things here. Memories are the obvious one -
 * they cannot be regenerated from anything, because they came out of conversations that are
 * gone - and the training corpus is the other. Everything else is either regenerable (the
 * photo index, which is derived from photos that are still there) or enormous and
 * re-downloadable (the models, which are gigabytes and live on a public hub). What goes in is
 * decided by that distinction rather than by what is easy to copy.
 *
 * JSON rather than the database file. A raw SQLite copy is exact and ties the backup to a
 * schema version for ever; this has to survive the app being rebuilt, renamed, or reinstalled
 * a year later, and a format it can version is what makes that possible.
 */
object BackupFormat {

  /** Raised only when an older build could not read a newer backup correctly. */
  const val FORMAT = 1

  const val MANIFEST = "manifest.json"
  const val MEMORIES = "memories.json"
  const val SETTINGS = "settings.json"

  /** Training packs keep their own names inside this folder. */
  const val PACKS = "training/"

  fun fileName(at: Long): String =
    "lin-backup-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(at))}.zip"

  fun manifestJson(appVersion: String, createdAt: Long, memories: Int, packs: Int): String =
    JSONObject()
      .put("format", FORMAT)
      .put("appVersion", appVersion)
      .put("createdAt", createdAt)
      .put("memories", memories)
      .put("packs", packs)
      .toString()

  /**
   * The manifest, or null if this is not a backup this build can read.
   *
   * A newer format is refused rather than guessed at. Reading half of a file somebody is
   * trusting with the only copy of their memories, and silently dropping the half that was
   * not understood, is worse than saying it cannot be read.
   */
  fun readManifest(json: String): Manifest? {
    val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val format = parsed.optInt("format", 0)
    if (format <= 0 || format > FORMAT) return null
    return Manifest(
      format = format,
      appVersion = parsed.optString("appVersion"),
      createdAt = parsed.optLong("createdAt"),
      memories = parsed.optInt("memories"),
      packs = parsed.optInt("packs"),
    )
  }

  fun memoriesJson(stored: List<StoredMemory>): String {
    val array = JSONArray()
    for (entry in stored) {
      val vector = JSONArray()
      entry.vector.forEach { vector.put(it.toDouble()) }
      array.put(
        JSONObject()
          .put("text", entry.memory.text)
          .put("source", entry.memory.source.name)
          .put("reference", entry.memory.reference)
          .put("createdAt", entry.memory.createdAt)
          .put("vector", vector)
      )
    }
    return JSONObject().put("memories", array).toString()
  }

  /**
   * The memories in a backup.
   *
   * Lenient about each row and strict about the whole: a row missing its text is dropped,
   * because there is nothing to restore, but an unreadable file yields nothing rather than a
   * partial list that would look like a successful restore of fewer memories.
   *
   * The id is deliberately not carried. Ids belong to the database a memory came out of, and
   * restoring them would either collide with what is already there or quietly overwrite it.
   */
  fun readMemories(json: String): List<StoredMemory> {
    val array =
      runCatching { JSONObject(json).getJSONArray("memories") }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
      val row = array.optJSONObject(index) ?: return@mapNotNull null
      val text = row.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val rawVector = row.optJSONArray("vector")
      val vector =
        FloatArray(rawVector?.length() ?: 0) { rawVector?.optDouble(it, 0.0)?.toFloat() ?: 0f }
      StoredMemory(
        Memory(
          text = text,
          source =
            MemorySource.entries.firstOrNull { it.name == row.optString("source") }
              ?: MemorySource.TOLD,
          reference = row.optString("reference"),
          createdAt = row.optLong("createdAt"),
        ),
        vector,
      )
    }
  }

  /**
   * Which of [candidates] are not already held.
   *
   * Restoring is additive and never deletes, which is the only safe default: the common case
   * is an empty app where it makes no difference, and the dangerous case is restoring onto a
   * populated one, where replacing would throw away everything learnt since the backup was
   * made.
   *
   * Matched on text rather than on id, because ids do not survive the trip. Case and
   * surrounding space are ignored - the same fact written twice is one fact.
   */
  fun notAlreadyHeld(candidates: List<StoredMemory>, existing: List<Memory>): List<StoredMemory> {
    val held = existing.map { it.text.trim().lowercase() }.toMutableSet()
    return candidates.filter { held.add(it.memory.text.trim().lowercase()) }
  }
}
