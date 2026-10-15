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

package com.lin.agent.core.voice

import java.util.Locale

/** One voice the phone can speak with, as far as choosing between them is concerned. */
data class VoiceOption(
  /** The engine's own name for it: `en-gb-x-gbb-local`. Stored, never shown as-is. */
  val name: String,
  val language: String,
  val country: String,
  /** The engine's own 100-500 scale. Comparable only within one engine. */
  val quality: Int,
  /** True when speaking means sending the words to somebody's server. */
  val needsNetwork: Boolean,
  val installed: Boolean = true,
)

/**
 * The voices worth offering, best first.
 *
 * Offline before quality, which is the opposite of what a ranking by the engine's own score
 * would do and is the right order here: a network voice means every spoken reply is sent to
 * somebody's server to be read out. That is exactly what this app does not do with anything
 * else, so a better-sounding voice that leaks the answer is not better. They are still
 * offered - it is the user's phone - but they are offered second and labelled.
 *
 * Voices for other languages are dropped only when there is something in the user's own
 * language to drop them for. A phone with no English voices and a user asking in English
 * should see what it does have rather than an empty list.
 */
fun usableVoices(all: List<VoiceOption>, language: String): List<VoiceOption> {
  val installed = all.filter { it.installed }
  val wanted = language.lowercase().take(2)
  val matching = installed.filter { it.language.lowercase().take(2) == wanted }
  val pool = matching.ifEmpty { installed }
  return pool.sortedWith(
    compareBy({ if (it.needsNetwork) 1 else 0 }, { -it.quality }, { it.name })
  )
}

/**
 * A voice's name as a person would read it.
 *
 * Engine names are not for showing: `en-gb-x-gbb-local` says English, Britain, variant gbb,
 * on the device - which is four useful facts in a shape nobody should have to decode. The
 * variant letters are kept because they are the only thing telling two otherwise identical
 * voices apart, and dropping them would leave a list of five identical rows.
 */
fun voiceLabel(option: VoiceOption): String {
  val place =
    runCatching {
        Locale.Builder().setLanguage(option.language).setRegion(option.country).build()
          .getDisplayName(Locale.ENGLISH)
      }
      .getOrNull()
      .orEmpty()
      .ifBlank { option.language }

  val variant = VARIANT.find(option.name)?.groupValues?.getOrNull(1).orEmpty()
  val where = if (option.needsNetwork) "online" else "on this phone"

  return listOf(place, variant, where).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The voice to speak with: the chosen one if it is still there, otherwise the best available.
 *
 * A voice can vanish between one reply and the next - an engine is switched, a language pack
 * is removed - and the choice is stored as a name rather than as an object for exactly that
 * reason. Falling back silently to the best one beats failing to speak at all; the settings
 * screen is where the user finds out their choice is gone.
 */
fun chosenVoice(available: List<VoiceOption>, chosenName: String): VoiceOption? =
  available.firstOrNull { it.name == chosenName } ?: available.firstOrNull()

private val VARIANT = Regex("-x-([a-z0-9]+)(?:#|-|$)")
