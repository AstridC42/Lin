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

import android.util.Log
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EmbeddingGemma, through LiteRT-LM.
 *
 * The interface's own note said MediaPipe's `UniversalEmbedder`, which was the plan before
 * LiteRT-LM 0.18 arrived with an `EmbeddingEngine` of its own. Using it means one runtime and
 * one AAR for both the chat model and the embedder, rather than two native stacks in a phone's
 * memory - so that is what this does, and the interface note is corrected rather than honoured.
 *
 * Loaded on demand and closed by the caller. Embedding is bursty - an indexing run, or one
 * search, then nothing for hours - and half a gigabyte held between bursts comes out of the
 * chat model's headroom on a phone that has exactly enough.
 *
 * The accelerator walks the same chain as the chat engine, for the same reason: whether a
 * vendor library is loadable is not something a device name predicts.
 */
class LiteRtEmbedder(
  private val modelPath: String,
  private val cacheDir: String? = null,
  private val accelerators: List<DeviceChoice.Accelerator> =
    DeviceChoice.acceleratorChain(DeviceChoice.Accelerator.GPU),
) : Embedder {

  @Volatile private var engine: EmbeddingEngine? = null

  /**
   * EmbeddingGemma 2's shared space, which every build of it maps into.
   *
   * Stated rather than discovered, because it is read before the first embedding - a store has
   * to know what width to expect. If a model ever disagreed, [cosine] refusing to compare
   * across widths is what keeps that from silently ranking by noise.
   */
  override val dimensions: Int = DIMENSIONS

  override suspend fun embed(parts: List<EmbedPart>): FloatArray =
    withContext(Dispatchers.IO) {
      if (parts.isEmpty()) return@withContext FloatArray(0)
      val live = ensureLoaded() ?: return@withContext FloatArray(0)
      val inputs = parts.mapNotNull(::asInputData)
      if (inputs.isEmpty()) return@withContext FloatArray(0)

      runCatching {
          live
            .computeEmbedding(
              inputs,
              // Normalised, because every comparison downstream is a cosine and normalising
              // once here is cheaper and more accurate than doing it per query.
              EmbeddingOptions(/* normalize = */ true),
            )
            .embedding
        }
        .onFailure { Log.w(TAG, "Could not embed", it) }
        .getOrDefault(FloatArray(0))
    }

  private fun asInputData(part: EmbedPart): InputData? =
    when (part) {
      is EmbedPart.Text -> InputData.Text(part.text)
      is EmbedPart.Image -> InputData.Image(part.bytes)
      // Audio takes a path through the engine this has no caller for yet, and guessing at the
      // encoding it wants would be inventing a format. Dropped rather than mis-sent.
      is EmbedPart.Audio -> null
    }

  @Synchronized
  private fun ensureLoaded(): EmbeddingEngine? {
    engine?.let {
      return it
    }
    if (!File(modelPath).exists()) {
      Log.w(TAG, "No embedding model at $modelPath")
      return null
    }

    for (attempt in accelerators) {
      val started =
        runCatching {
            EmbeddingEngine(
                EmbeddingEngineConfig(
                  modelPath,
                  asLiteRtBackend(attempt),
                  // Images only where the engine itself is on the GPU, for the reason the chat
                  // engine has: a CPU attempt carrying a GPU vision encoder fails exactly as
                  // the attempt before it did, which makes the fallback pointless.
                  if (attempt == DeviceChoice.Accelerator.GPU) asLiteRtBackend(attempt) else null,
                  null,
                  cacheDir,
                )
              )
              .also { it.initialize() }
          }
          .onFailure { Log.w(TAG, "Embedder would not load on $attempt", it) }
          .getOrNull()
      if (started != null) {
        engine = started
        return started
      }
    }
    return null
  }

  override fun close() {
    synchronized(this) {
      runCatching { engine?.close() }
      engine = null
    }
  }

  private fun asLiteRtBackend(accelerator: DeviceChoice.Accelerator) =
    when (accelerator) {
      DeviceChoice.Accelerator.CPU -> com.google.ai.edge.litertlm.Backend.CPU()
      DeviceChoice.Accelerator.GPU -> com.google.ai.edge.litertlm.Backend.GPU()
      DeviceChoice.Accelerator.NPU -> com.google.ai.edge.litertlm.Backend.NPU("")
    }

  companion object {
    private const val TAG = "LinEmbedder"

    /** EmbeddingGemma 2's shared space: text, images and audio all land in these 768. */
    const val DIMENSIONS = 768
  }
}
