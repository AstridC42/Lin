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

package com.lin.agent.engine

/** One thing to embed. Text, an image, or a clip - all into the same vector space. */
sealed interface EmbedPart {
  data class Text(val text: String) : EmbedPart

  data class Image(val path: String) : EmbedPart

  data class Audio(val path: String) : EmbedPart
}

/**
 * Turning text and media into vectors, for search over memory and the photo library.
 *
 * Deliberately not part of [LlmBackend]. It runs on a different runtime - MediaPipe's
 * UniversalEmbedder rather than LiteRT-LM's Engine - and conflating the two would put a vector
 * store behind a chat abstraction.
 *
 * One model serves both memory and media because EmbeddingGemma 2's 740M build maps text, images,
 * video and audio into one shared 768-dimensional space. That is what makes "the photo I was
 * talking about on Tuesday" a single query across notes and images rather than two and a join.
 *
 * Unlike the chat model, an embedder is not resident. Embedding is bursty - an indexing run, or a
 * search, then nothing for hours - and holding half a gigabyte between bursts takes it from the
 * chat model's context for no benefit. Hence [close].
 */
interface Embedder : AutoCloseable {
  val dimensions: Int

  suspend fun embed(parts: List<EmbedPart>): FloatArray
}
