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

package com.lin.agent.core.models

import android.os.Build

/** What a model is for. The app loads at most one of each at a time. */
enum class ModelRole {
  /** Holds the conversation, routes, and writes briefs. Resident. */
  CHAT,

  /** Turns text and media into vectors. Loaded for a burst, then released. */
  EMBED,
}

/**
 * One model this app knows how to run.
 *
 * Deliberately a short hand-written list rather than a fetched allowlist. The previous app read a
 * remote catalogue versioned by *its own* app version, which meant pinning a file on somebody
 * else's branch and discovering - the hard way - that a stale pin left a whole feature with no
 * model at all. This app needs two or three known files, so the list is code and a change to it
 * is a commit.
 */
data class CatalogModel(
  val name: String,
  val role: ModelRole,
  /** Hugging Face repo, for the download in Phase 1b. */
  val repoId: String,
  /** The file to use when no SoC-specific build applies. */
  val file: String,
  val sizeBytes: Long,
  /** Gigabytes of device RAM below which this should not be the default. */
  val minMemoryGb: Int,
  val contextTokens: Int,
  val supportsImages: Boolean = false,
  val supportsAudio: Boolean = false,
  /**
   * SoC-specific builds, keyed by `Build.SOC_MODEL`.
   *
   * Present from the start even though nothing selects one yet. The NPU builds for the embedder
   * exist on the Hub - including one for this phone's SM8750 - but running them needs vendor
   * runtime libraries on the device, and the documented path is a command-line binary rather than
   * an app. Google ships the model GPU/CPU-only in its own app, which is the clearest evidence
   * available that the path is not ready. Carrying the mapping now means enabling it later is
   * data rather than a refactor.
   */
  val socFiles: Map<String, String> = emptyMap(),
) {
  /** The file to download and load on this device. */
  fun fileFor(socModel: String = currentSocModel()): String = socFiles[socModel] ?: file
}

/** `Build.SOC_MODEL` where the platform has it, empty below API 31. */
fun currentSocModel(): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""

object ModelCatalog {

  /**
   * Gemma 4 E2B. The default chat model.
   *
   * Default rather than E4B even though this phone meets E4B's minimum exactly, because exactly is
   * not comfortably: 3.66 GB of weights plus an 8,192-token cache plus the system is the footprint
   * where Android's low-memory killer takes an interest, and an OOM kill runs no Java code on the
   * way down - so it leaves no stack trace and looks from outside like the app vanishing.
   */
  val GEMMA_4_E2B =
    CatalogModel(
      name = "Gemma 4 E2B",
      role = ModelRole.CHAT,
      repoId = "litert-community/gemma-4-E2B-it-litert-lm",
      file = "gemma-4-E2B-it.litertlm",
      sizeBytes = 2_594_275_328,
      minMemoryGb = 8,
      contextTokens = 8192,
      supportsImages = true,
      supportsAudio = true,
    )

  /** Gemma 4 E4B. Better, and a setting rather than the default. See the note above. */
  val GEMMA_4_E4B =
    CatalogModel(
      name = "Gemma 4 E4B",
      role = ModelRole.CHAT,
      repoId = "litert-community/gemma-4-E4B-it-litert-lm",
      file = "gemma-4-E4B-it.litertlm",
      sizeBytes = 3_662_311_424,
      minMemoryGb = 12,
      contextTokens = 8192,
      supportsImages = true,
      supportsAudio = true,
    )

  /**
   * EmbeddingGemma 2, the 740M multimodal build.
   *
   * The 740M rather than the 270M text build, because media search is in scope: this is the same
   * 270M text backbone with a vision and an audio encoder attached, and everything lands in one
   * shared 768-dimensional space. So memory and media are searchable together rather than
   * separately, which is what makes "the photo I was talking about on Tuesday" a single query.
   */
  val EMBEDDING_GEMMA_2 =
    CatalogModel(
      name = "EmbeddingGemma 2",
      role = ModelRole.EMBED,
      repoId = "litert-community/embeddinggemma-2-740m-litert-lm",
      file = "embeddinggemma-2-740m.litertlm",
      sizeBytes = 484_622_336,
      minMemoryGb = 8,
      contextTokens = 8192,
      supportsImages = true,
      supportsAudio = true,
      socFiles =
        mapOf(
          "SM8750" to "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm",
          "SM8850" to "embeddinggemma-2-740m_Qualcomm_SM8850.litertlm",
          "SM8650" to "embeddinggemma-2-740m_Qualcomm_SM8650.litertlm",
          "SM8550" to "embeddinggemma-2-740m_Qualcomm_SM8550.litertlm",
          "QCS8275" to "embeddinggemma-2-740m_Qualcomm_QCS8275.litertlm",
          "MT6991" to "embeddinggemma-2-740m_MediaTek_MT6991.litertlm",
          "MT6993" to "embeddinggemma-2-740m_MediaTek_MT6993.litertlm",
          "Tensor G5" to "embeddinggemma-2-740m_Google_Tensor_G5.litertlm",
          "Tensor G6" to "embeddinggemma-2-740m_Google_Tensor_G6.litertlm",
        ),
    )

  val all = listOf(GEMMA_4_E2B, GEMMA_4_E4B, EMBEDDING_GEMMA_2)

  val chatModels = all.filter { it.role == ModelRole.CHAT }

  fun byName(name: String): CatalogModel? = all.firstOrNull { it.name == name }

  /**
   * The chat model to use when the user has not chosen, given the device's memory.
   *
   * Never returns one the device cannot hold: on a phone below E2B's own minimum there is nothing
   * sensible to default to, and saying so is better than loading something that will be killed.
   */
  fun defaultChatModel(memoryGb: Int): CatalogModel? =
    chatModels.filter { it.minMemoryGb <= memoryGb }.minByOrNull { it.sizeBytes }

  /** Whether [model] is at or below the device's comfortable limit. */
  fun isComfortable(model: CatalogModel, memoryGb: Int): Boolean =
    memoryGb > model.minMemoryGb
}
