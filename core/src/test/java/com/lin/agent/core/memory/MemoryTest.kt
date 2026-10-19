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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval, which has one failure worth most of the attention.
 *
 * Cosine similarity always produces a ranking. Ask it for the five closest memories to anything
 * and it returns five, however unrelated - and a model handed three irrelevant "memories" will
 * use them, in the user's own words, as though they were facts. That is how a recall feature
 * starts inventing things somebody never said, and it is worse than remembering nothing because
 * nothing marks a false memory as false.
 */
class MemoryTest {

  private fun memory(text: String) = Memory(text = text, source = MemorySource.TOLD)

  // ---- the floor ----

  @Test
  fun aQueryThatMatchesNothingReturnsNothing() {
    val results =
      rank(
        query = floatArrayOf(1f, 0f, 0f),
        candidates = listOf(memory("unrelated") to floatArrayOf(0f, 1f, 0f)),
      )
    assertTrue("an orthogonal vector is not a memory", results.isEmpty())
  }

  @Test
  fun anOppositeVectorIsNotARecall() {
    val results =
      rank(floatArrayOf(1f, 0f), listOf(memory("opposite") to floatArrayOf(-1f, 0f)))
    assertTrue(results.isEmpty())
  }

  @Test
  fun aCloseMatchIsReturned() {
    val results =
      rank(floatArrayOf(1f, 0f), listOf(memory("the same thing") to floatArrayOf(0.95f, 0.1f)))
    assertEquals(1, results.size)
    assertEquals("the same thing", results.first().memory.text)
  }

  @Test
  fun theStrongestMatchComesFirst() {
    val results =
      rank(
        floatArrayOf(1f, 0f),
        listOf(
          memory("near") to floatArrayOf(0.8f, 0.6f),
          memory("nearest") to floatArrayOf(1f, 0.05f),
          memory("far") to floatArrayOf(0f, 1f),
        ),
      )
    assertEquals(listOf("nearest", "near"), results.map { it.memory.text })
  }

  @Test
  fun noMoreThanTheLimitComesBack() {
    val candidates = (1..20).map { memory("note $it") to floatArrayOf(1f, 0f) }
    assertEquals(3, rank(floatArrayOf(1f, 0f), candidates, limit = 3).size)
  }

  // ---- comparing across models ----

  /**
   * Two different embedders produce two different spaces, and the overlap between them means
   * nothing. Scoring it anyway would rank memories written before a model change by noise.
   */
  @Test
  fun vectorsOfDifferentWidthsDoNotCompare() {
    assertEquals(0f, cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f)), 0f)
    assertTrue(
      rank(floatArrayOf(1f, 0f, 0f), listOf(memory("older model") to floatArrayOf(1f, 0f)))
        .isEmpty()
    )
  }

  /**
   * A memory saved before the embedder arrived is held but not searchable.
   *
   * Load-bearing now that `remember` keeps a fact whether or not an embedding model is
   * downloaded. It has to be inert in search rather than matching everything at score zero -
   * the floor already excludes it, and this pins that rather than leaving it to follow from
   * two other rules.
   */
  @Test
  fun aMemoryWithNoVectorNeverMatches() {
    val results =
      rank(
        floatArrayOf(1f, 0f, 0f),
        listOf(
          memory("saved before the embedder") to FloatArray(0),
          memory("searchable") to floatArrayOf(1f, 0f, 0f),
        ),
      )
    assertEquals(listOf("searchable"), results.map { it.memory.text })
  }

  @Test
  fun anEmptyOrZeroVectorScoresNothingRatherThanThrowing() {
    assertEquals(0f, cosine(FloatArray(0), FloatArray(0)), 0f)
    assertEquals(0f, cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
  }

  @Test
  fun anIdenticalVectorScoresOne() {
    assertEquals(1f, cosine(floatArrayOf(0.3f, 0.4f, 0.5f), floatArrayOf(0.3f, 0.4f, 0.5f)), 1e-6f)
  }

  /** Direction, not length: the same meaning at twice the magnitude is the same meaning. */
  @Test
  fun scaleDoesNotChangeSimilarity() {
    assertEquals(1f, cosine(floatArrayOf(1f, 2f), floatArrayOf(5f, 10f)), 1e-6f)
  }

  // ---- storage round trip ----

  @Test
  fun aVectorSurvivesBeingStoredAndReadBack() {
    val original = floatArrayOf(0.1f, -0.25f, 1f, 0f, -1f, 3.5e-3f)
    val restored = original.toBytes().toFloats()
    assertEquals(original.size, restored.size)
    original.indices.forEach { assertEquals(original[it], restored[it], 0f) }
  }

  @Test
  fun anEmptyVectorRoundTrips() {
    assertEquals(0, FloatArray(0).toBytes().toFloats().size)
  }

  /** A truncated blob loses the incomplete float rather than reading past the end. */
  @Test
  fun aTruncatedBlobIsReadAsFarAsItGoes() {
    val bytes = floatArrayOf(1f, 2f, 3f).toBytes()
    val restored = bytes.copyOf(bytes.size - 2).toFloats()
    assertEquals(2, restored.size)
    assertEquals(1f, restored[0], 0f)
    assertEquals(2f, restored[1], 0f)
  }

  /**
   * Written little-endian whatever the platform does.
   *
   * Every Android device today is little-endian, so this is a test for the day one is not -
   * where the failure would be vectors that rank wrongly, with nothing to show anything broke.
   */
  @Test
  fun bytesAreWrittenLittleEndian() {
    // 1.0f is 0x3F800000, so little-endian puts the 0x3F last.
    assertEquals(listOf(0, 0, 0x80.toByte(), 0x3F), floatArrayOf(1f).toBytes().toList())
  }
}
