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
 * One downloadable build of a model: the file on the Hub, and exactly how long it is.
 *
 * The two travel together, and that is the whole point of the type. They used to be separate
 * fields - a file name that varied by chip, and one size that did not - and the downloader
 * resolved them independently: it fetched the chip-specific build and then checked its length
 * against the portable build's. On a phone with an SoC build published, that is a download that
 * can only ever fail its own length check, five times over, for about fifteen gigabytes of
 * somebody's data allowance. Paired, the mistake is not expressible.
 */
data class ModelBuild(val file: String, val sizeBytes: Long)

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
  /** Hugging Face repo. Apache-2.0 and ungated, so no token and no licence screen. */
  val repoId: String,
  /** The build that runs anywhere: CPU and GPU, no vendor runtime needed. */
  val portable: ModelBuild,
  /** Gigabytes of device RAM below which this should not be the default. */
  val minMemoryGb: Int,
  val contextTokens: Int,
  val supportsImages: Boolean = false,
  val supportsAudio: Boolean = false,
  /**
   * NPU builds, keyed by `Build.SOC_MODEL`.
   *
   * Carried as data and deliberately not selected by anything - see [build]. Running one needs
   * vendor runtime libraries on the device and the documented path is a command-line binary
   * rather than an app; Google ships these models GPU/CPU-only in its own app, which is the
   * clearest evidence available that the path is not ready. The sizes are here because the only
   * thing standing between this map and a working NPU download is a length to check against, and
   * leaving that to be looked up later is how the last bug happened.
   */
  val socBuilds: Map<String, ModelBuild> = emptyMap(),
) {
  /**
   * The build to use on a device whose chip reports [socModel]. Always the portable one.
   *
   * A function that ignores its argument, on purpose, and worth explaining rather than
   * simplifying away. This is the one seam where a chip name could decide which file gets
   * downloaded, and it used to: it returned the NPU build whenever the Hub had one for the chip.
   * Keeping the parameter is what lets a test name a chip and assert that nothing happens -
   * `Build.SOC_MODEL` is empty off-device, so a no-argument version could not be tested at all.
   *
   * Why there is no selection here: these builds are not interchangeable. They are bigger than
   * the portable build rather than smaller - the SM8750 build of E2B is 3.02 GB against 2.59 GB -
   * and one loaded without its vendor runtime fails inside native code, where there is no
   * exception to catch and no message to show. Choosing one has to be a decision with a working
   * loader behind it, not a map lookup on a chip name.
   */
  fun buildFor(socModel: String): ModelBuild = portable

  /** The build to download and load on this device. */
  val build: ModelBuild
    get() = buildFor(currentSocModel())
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
      // Read off the Hub, not copied from the fork's allowlist, which says 2_594_275_328. The
      // downloader verifies length, so a figure that is merely close would make every completed
      // download look wrong.
      portable = ModelBuild("gemma-4-E2B-it.litertlm", 2_588_147_712),
      minMemoryGb = 8,
      contextTokens = 8192,
      supportsImages = true,
      supportsAudio = true,
      // E2B has NPU builds and E4B does not, so there is no uniform rule here - and the naming
      // differs from the embedder's, lowercase where that one is capitalised. Both facts came
      // from listing the repositories rather than from assuming symmetry.
      socBuilds =
        mapOf(
          "SM8750" to
            ModelBuild("gemma-4-E2B-it_qualcomm_sm8750.litertlm", 3_016_294_400),
          "QCS8275" to
            ModelBuild("gemma-4-E2B-it_qualcomm_qcs8275.litertlm", 3_294_593_024),
          "Tensor G5" to
            ModelBuild("gemma-4-E2B-it_Google_Tensor_G5.litertlm", 3_113_545_589),
          "Tensor G6" to
            ModelBuild("gemma-4-E2B-it_Google_Tensor_G6.litertlm", 3_313_938_293),
        ),
    )

  /**
   * Gemma 4 E4B. Better, and a setting rather than the default. See the note above.
   *
   * No SoC builds at all, unlike E2B - so on a phone whose chip has an NPU variant, the smaller
   * model is the one with more ways to run.
   */
  val GEMMA_4_E4B =
    CatalogModel(
      name = "Gemma 4 E4B",
      role = ModelRole.CHAT,
      repoId = "litert-community/gemma-4-E4B-it-litert-lm",
      // Also from the Hub; the allowlist says 3_662_311_424.
      portable = ModelBuild("gemma-4-E4B-it.litertlm", 3_659_530_240),
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
      portable = ModelBuild("embeddinggemma-2-740m.litertlm", 484_622_336),
      minMemoryGb = 8,
      contextTokens = 8192,
      supportsImages = true,
      supportsAudio = true,
      socBuilds =
        mapOf(
          "SM8750" to
            ModelBuild("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm", 543_522_816),
          "SM8850" to
            ModelBuild("embeddinggemma-2-740m_Qualcomm_SM8850.litertlm", 559_448_064),
          "SM8650" to
            ModelBuild("embeddinggemma-2-740m_Qualcomm_SM8650.litertlm", 562_659_328),
          "SM8550" to
            ModelBuild("embeddinggemma-2-740m_Qualcomm_SM8550.litertlm", 562_905_088),
          "QCS8275" to
            ModelBuild("embeddinggemma-2-740m_Qualcomm_QCS8275.litertlm", 562_888_704),
          "MT6991" to
            ModelBuild("embeddinggemma-2-740m_MediaTek_MT6991.litertlm", 895_008_768),
          "MT6993" to
            ModelBuild("embeddinggemma-2-740m_MediaTek_MT6993.litertlm", 864_681_984),
          "Tensor G5" to
            ModelBuild("embeddinggemma-2-740m_Google_Tensor_G5.litertlm", 657_656_500),
          "Tensor G6" to
            ModelBuild("embeddinggemma-2-740m_Google_Tensor_G6.litertlm", 615_713_460),
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
    chatModels.filter { it.minMemoryGb <= memoryGb }.minByOrNull { it.build.sizeBytes }

  /** Whether [model] is at or below the device's comfortable limit. */
  fun isComfortable(model: CatalogModel, memoryGb: Int): Boolean =
    memoryGb > model.minMemoryGb
}
