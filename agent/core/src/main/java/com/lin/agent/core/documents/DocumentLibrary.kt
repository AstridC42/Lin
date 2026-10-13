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

package com.lin.agent.core.documents

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A document Lin has been given, or has written. */
data class KnownDoc(
  /** The content URI, as a string, so this stays a plain record. */
  val uri: String,
  val name: String,
  val addedAt: Long,
  /** True when Lin wrote it, which is what makes it safe to replace. */
  val written: Boolean = false,
)

/**
 * The documents Lin may read, and nothing else on the phone.
 *
 * An allowlist by construction rather than by policy. The app holds no storage permission for
 * documents at all: it can read a file because its owner handed over that one file through the
 * picker or a share, and the persisted URI permission that came with it is the whole of its
 * access. There is no code path that could widen this, because there is no permission to widen.
 */
class DocumentLibrary(context: Context) {

  private val file = File(context.filesDir, NAME)

  fun all(): List<KnownDoc> =
    runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
          val row = array.optJSONObject(index) ?: return@mapNotNull null
          val uri = row.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
          KnownDoc(
            uri = uri,
            name = row.optString("name").ifEmpty { "document" },
            addedAt = row.optLong("addedAt"),
            written = row.optBoolean("written"),
          )
        }
      }
      .onFailure { Log.w(TAG, "Could not read the document list", it) }
      .getOrDefault(emptyList())
      .sortedByDescending { it.addedAt }

  /** Adds one, replacing any earlier entry for the same URI rather than duplicating it. */
  fun add(document: KnownDoc) {
    save(all().filterNot { it.uri == document.uri } + document)
  }

  fun forget(uri: String) = save(all().filterNot { it.uri == uri })

  /** The document [query] names, or null. See [matching]. */
  fun find(query: String): KnownDoc? = matching(all(), query)

  private fun save(documents: List<KnownDoc>) {
    runCatching {
        val array = JSONArray()
        // Newest first and capped: this is what Lin can reach, not an archive of everything
        // ever opened, and an unbounded list is one more thing to go wrong on a full disk.
        documents.sortedByDescending { it.addedAt }.take(KEEP).forEach { document ->
          array.put(
            JSONObject()
              .put("uri", document.uri)
              .put("name", document.name)
              .put("addedAt", document.addedAt)
              .put("written", document.written)
          )
        }
        file.writeText(array.toString())
      }
      .onFailure { Log.w(TAG, "Could not save the document list", it) }
  }

  private companion object {
    const val TAG = "LinDocumentLibrary"
    const val NAME = "documents.json"
    const val KEEP = 100
  }
}

/**
 * The document [query] most likely means, or null.
 *
 * Loose on purpose, and in a specific order, because the query is a name somebody said out loud
 * and a model passed on. "the rent agreement" should reach "Rent agreement (2026).docx", and
 * "rent" should too.
 *
 * Exact beats prefix beats contains beats word overlap, and newest breaks a tie. Without that
 * ordering, asking for "notes" where both "Notes.txt" and "Meeting notes, January.docx" exist
 * would come down to list order - which is how an assistant reads the wrong contract.
 */
fun matching(documents: List<KnownDoc>, query: String): KnownDoc? {
  val needle = query.trim().lowercase().removeSuffix(".").trim()
  if (needle.isEmpty() || documents.isEmpty()) return null

  // A query that is nothing but filler matches nothing. Without this, "the" reaches "The
  // Report.docx" through the prefix rule below - a guess dressed up as a match. Checked by
  // naming the filler rather than by length, because a short query can be a real name: "cv"
  // should still find "CV.pdf".
  val words = needle.split(NOT_A_WORD).filter { it.isNotEmpty() }
  if (words.isNotEmpty() && words.all { it in FILLER }) return null

  fun base(document: KnownDoc) = document.name.lowercase().substringBeforeLast('.').trim()

  val byRecency = documents.sortedByDescending { it.addedAt }

  byRecency.firstOrNull { it.name.lowercase() == needle || base(it) == needle }?.let {
    return it
  }
  byRecency.firstOrNull { base(it).startsWith(needle) }?.let {
    return it
  }
  byRecency.firstOrNull { base(it).contains(needle) }?.let {
    return it
  }

  // Last resort: the query's words against the name's. "the rent agreement" against
  // "Rent agreement (2026)" overlaps on two, and a filler word on its own is not enough -
  // otherwise "the report" matches "The" in anything.
  val wanted = needle.split(NOT_A_WORD).filter { it.length > 2 }.toSet()
  if (wanted.isEmpty()) return null
  return byRecency
    .map { document -> document to base(document).split(NOT_A_WORD).toSet().intersect(wanted) }
    .filter { (_, shared) -> shared.isNotEmpty() }
    .maxByOrNull { (_, shared) -> shared.size }
    ?.first
}

private val NOT_A_WORD = Regex("[^a-z0-9]+")

/**
 * Words that carry no intent on their own.
 *
 * Not a general stop list - only what somebody says around a document's name when asking for
 * it. Anything not here is treated as part of the name.
 */
private val FILLER =
  setOf(
    "the",
    "a",
    "an",
    "my",
    "that",
    "this",
    "it",
    "and",
    "of",
    "to",
    "for",
    "me",
    "please",
    "document",
    "file",
  )
