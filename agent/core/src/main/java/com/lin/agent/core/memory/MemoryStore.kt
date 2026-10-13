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

package com.lin.agent.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Lin remembers, and the vectors to find it by.
 *
 * SQLite rather than a vector database, and the search is a full scan scored in Kotlin. That is
 * a real ceiling and worth stating: a few thousand memories compare in a few milliseconds, a
 * hundred thousand photos would not. The photo library will need an index; notes will not, and
 * building one now would be solving a problem this app does not have yet.
 *
 * The vectors live beside the text in the same row, so there is no second store to keep in step.
 * A vector written by a different embedder is not deleted on sight - it simply stops matching,
 * because [cosine] refuses to compare across dimensions - and re-indexing can replace it later.
 */
class MemoryStore(context: Context) {

  private val helper = Helper(context.applicationContext)

  /**
   * Saves something to remember. Returns its id.
   *
   * [vector] may be empty, and that is the point. Keeping a fact and being able to search for it
   * are two problems, and this conflated them: with no embedding model downloaded, "remember my
   * name is Otto" stored nothing at all. A memory with no vector is held, shown on the memory
   * screen and backfilled by [withoutVectors] once an embedder exists - which is strictly better
   * than discarding what somebody asked to be kept because a 484 MB download is missing.
   */
  suspend fun remember(memory: Memory, vector: FloatArray): Long =
    withContext(Dispatchers.IO) {
      runCatching {
          helper.writableDatabase.insert(
            TABLE,
            null,
            ContentValues().apply {
              put(TEXT, memory.text)
              put(SOURCE, memory.source.name)
              put(REFERENCE, memory.reference)
              put(CREATED_AT, memory.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis())
              // Null rather than an empty blob, so "not embedded yet" is a state the
              // query below can select on rather than a length somebody has to remember.
              if (vector.isEmpty()) putNull(VECTOR) else put(VECTOR, vector.toBytes())
            },
          )
        }
        .onFailure { Log.w(TAG, "Could not save a memory", it) }
        .getOrDefault(-1L)
    }

  /**
   * The memories closest to [query].
   *
   * Reads every row and scores in Kotlin - see the note on the class. [sources] narrows the scan
   * where the caller already knows what it wants, which is what keeps a text recall from paging
   * through a photo index.
   */
  suspend fun search(
    query: FloatArray,
    limit: Int = DEFAULT_LIMIT,
    minScore: Float = DEFAULT_MIN_SCORE,
    sources: Set<MemorySource> = MemorySource.entries.toSet(),
  ): List<Scored> =
    withContext(Dispatchers.IO) {
      if (query.isEmpty() || sources.isEmpty()) return@withContext emptyList()
      runCatching { rank(query, load(sources), limit, minScore) }
        .onFailure { Log.w(TAG, "Could not search memories", it) }
        .getOrDefault(emptyList())
    }

  /** Everything remembered, newest first. For a screen that lets somebody read or delete it. */
  suspend fun all(limit: Int = 200): List<Memory> =
    withContext(Dispatchers.IO) {
      runCatching {
          helper.readableDatabase
            .query(TABLE, COLUMNS, null, null, null, null, "$CREATED_AT DESC", limit.toString())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMemory()) } }
        }
        .onFailure { Log.w(TAG, "Could not read memories", it) }
        .getOrDefault(emptyList())
    }

  /**
   * Memories saved before there was an embedder, oldest first.
   *
   * The backfill queue. Bounded because this runs on the way into a search, and a thousand
   * embeddings is not something to do while somebody waits for an answer.
   */
  suspend fun withoutVectors(limit: Int = 25): List<Memory> =
    withContext(Dispatchers.IO) {
      runCatching {
          helper.readableDatabase
            .query(
              TABLE,
              COLUMNS,
              "$VECTOR IS NULL",
              null,
              null,
              null,
              "$CREATED_AT ASC",
              limit.toString(),
            )
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMemory()) } }
        }
        .onFailure { Log.w(TAG, "Could not list un-embedded memories", it) }
        .getOrDefault(emptyList())
    }

  /** Attaches a vector to a memory that was saved without one. */
  suspend fun setVector(id: Long, vector: FloatArray): Boolean =
    withContext(Dispatchers.IO) {
      if (vector.isEmpty()) return@withContext false
      runCatching {
          helper.writableDatabase.update(
            TABLE,
            ContentValues().apply { put(VECTOR, vector.toBytes()) },
            "$ID = ?",
            arrayOf(id.toString()),
          ) > 0
        }
        .onFailure { Log.w(TAG, "Could not attach a vector to $id", it) }
        .getOrDefault(false)
    }

  suspend fun forget(id: Long): Boolean =
    withContext(Dispatchers.IO) {
      runCatching { helper.writableDatabase.delete(TABLE, "$ID = ?", arrayOf(id.toString())) > 0 }
        .onFailure { Log.w(TAG, "Could not forget $id", it) }
        .getOrDefault(false)
    }

  /** How many are stored, so a screen can say "nothing yet" honestly. */
  suspend fun count(): Int =
    withContext(Dispatchers.IO) {
      runCatching {
          helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
          }
        }
        .getOrDefault(0)
    }

  private fun load(sources: Set<MemorySource>): List<Pair<Memory, FloatArray>> {
    val placeholders = sources.joinToString(",") { "?" }
    return helper.readableDatabase
      .query(
        TABLE,
        COLUMNS + VECTOR,
        "$SOURCE IN ($placeholders) AND $VECTOR IS NOT NULL",
        sources.map { it.name }.toTypedArray(),
        null,
        null,
        null,
      )
      .use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            val bytes = cursor.getBlob(cursor.getColumnIndexOrThrow(VECTOR)) ?: continue
            add(cursor.toMemory() to bytes.toFloats())
          }
        }
      }
  }

  private fun android.database.Cursor.toMemory(): Memory =
    Memory(
      id = getLong(getColumnIndexOrThrow(ID)),
      text = getString(getColumnIndexOrThrow(TEXT)).orEmpty(),
      source =
        MemorySource.entries.firstOrNull {
          it.name == getString(getColumnIndexOrThrow(SOURCE))
        } ?: MemorySource.TOLD,
      reference = getString(getColumnIndexOrThrow(REFERENCE)).orEmpty(),
      createdAt = getLong(getColumnIndexOrThrow(CREATED_AT)),
    )

  private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        "CREATE TABLE $TABLE (" +
          "$ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
          "$TEXT TEXT NOT NULL, " +
          "$SOURCE TEXT NOT NULL, " +
          "$REFERENCE TEXT NOT NULL DEFAULT '', " +
          "$CREATED_AT INTEGER NOT NULL, " +
          "$VECTOR BLOB)"
      )
      // Searching narrows by source before scoring, and listing orders by date.
      db.execSQL("CREATE INDEX ${TABLE}_source ON $TABLE ($SOURCE)")
      db.execSQL("CREATE INDEX ${TABLE}_created ON $TABLE ($CREATED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
      // Nothing to migrate from yet. When there is, it migrates - a memory somebody asked to be
      // kept is not something to drop because a column was added.
      Log.i(TAG, "Memory store $from -> $to")
    }
  }

  private companion object {
    const val TAG = "LinMemoryStore"
    const val NAME = "memories.db"
    const val VERSION = 1

    const val TABLE = "memories"
    const val ID = "id"
    const val TEXT = "text"
    const val SOURCE = "source"
    const val REFERENCE = "reference"
    const val CREATED_AT = "created_at"
    const val VECTOR = "vector"

    val COLUMNS = arrayOf(ID, TEXT, SOURCE, REFERENCE, CREATED_AT)
  }
}
