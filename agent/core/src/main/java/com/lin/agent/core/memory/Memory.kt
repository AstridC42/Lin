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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Where something remembered came from. */
enum class MemorySource {
  /** The user said to remember it. The most trustworthy kind, and never auto-expired. */
  TOLD,

  /** Lifted from a conversation afterwards by a digest. */
  CONVERSATION,

  /** A photo, indexed by what is in it. [Memory.reference] holds the image. */
  PHOTO,
}

/** One thing Lin remembers. */
data class Memory(
  val id: Long = 0,
  val text: String,
  val source: MemorySource,
  /** Where the original is, for a photo. Empty for text. */
  val reference: String = "",
  val createdAt: Long = 0,
)

/** A memory and how well it matched. */
data class Scored(val memory: Memory, val score: Float)

/**
 * How close two embeddings are, between -1 and 1.
 *
 * Returns 0 for vectors of different lengths rather than throwing or comparing the overlap. A
 * length mismatch means they came from different models - the embedder was changed, or a chip
 * build was swapped in - and the honest answer to "how similar are these" across two vector
 * spaces is that the question does not apply. Scoring it anyway would quietly rank old memories
 * by noise.
 */
fun cosine(a: FloatArray, b: FloatArray): Float {
  if (a.size != b.size || a.isEmpty()) return 0f
  var dot = 0.0
  var normA = 0.0
  var normB = 0.0
  for (i in a.indices) {
    dot += a[i].toDouble() * b[i]
    normA += a[i].toDouble() * a[i]
    normB += b[i].toDouble() * b[i]
  }
  if (normA == 0.0 || normB == 0.0) return 0f
  return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
}

/**
 * The best matches for a query vector, strongest first.
 *
 * [minScore] exists because the alternative is worse than returning nothing. Cosine similarity
 * always produces a ranking, so without a floor every query returns its [limit] closest
 * memories however unrelated they are - and a model handed three irrelevant "memories" will use
 * them, which is how a recall feature starts inventing things the user never said.
 */
fun rank(
  query: FloatArray,
  candidates: List<Pair<Memory, FloatArray>>,
  limit: Int = DEFAULT_LIMIT,
  minScore: Float = DEFAULT_MIN_SCORE,
): List<Scored> =
  candidates
    .asSequence()
    .map { (memory, vector) -> Scored(memory, cosine(query, vector)) }
    .filter { it.score >= minScore }
    .sortedByDescending { it.score }
    .take(limit)
    .toList()

/** Enough to answer with, few enough not to crowd out the conversation. */
const val DEFAULT_LIMIT = 5

/**
 * The floor, chosen to be unhelpful rather than wrong.
 *
 * On a normalised 768-dimensional space, unrelated text lands near zero and a genuine topical
 * match sits well above this. Set too low, recall returns noise with the confidence of a fact;
 * set too high, it says it remembers nothing when it does. Being too strict is recoverable - the
 * user can rephrase - and being too loose is not, because nothing marks a false memory as false.
 */
const val DEFAULT_MIN_SCORE = 0.35f

/**
 * An embedding as bytes, for storage.
 *
 * Little-endian and explicit about it. The default order is the platform's, which is the same on
 * every Android device shipping today and would still be a silent corruption the day it is not -
 * vectors written on one phone and read on another would simply rank wrongly, with nothing to
 * show that anything had gone wrong.
 */
fun FloatArray.toBytes(): ByteArray {
  val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
  forEach(buffer::putFloat)
  return buffer.array()
}

/** The inverse of [toBytes]. An incomplete trailing float is dropped rather than guessed. */
fun ByteArray.toFloats(): FloatArray {
  val count = size / Float.SIZE_BYTES
  val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
  return FloatArray(count) { buffer.getFloat() }
}
