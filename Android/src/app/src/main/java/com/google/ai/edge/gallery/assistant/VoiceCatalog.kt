/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.assistant

/**
 * One voice the device's speech engine can speak in, as it should be shown to a person.
 *
 * @property id the engine's own name for the voice, which is what gets stored and set.
 * @property label a readable name.
 * @property languageTag the voice's locale, for grouping.
 * @property needsNetwork true when the voice is synthesised on Google's servers rather than on the
 *   phone. Worth showing: it is the difference between an assistant that answers in a tunnel and
 *   one that goes quiet.
 */
data class SpokenVoice(
  val id: String,
  val label: String,
  val languageTag: String,
  val needsNetwork: Boolean,
)

/**
 * Turns the engine's voice names into something worth putting in a list.
 *
 * Speech engines name voices for themselves, not for people: `en-us-x-sfg#male_1-local`,
 * `en-GB-language`, `en-us-x-tpd-network`. A picker that shows those is a picker nobody can use,
 * and there is no API that returns a friendly name - so the name has to be taken apart.
 *
 * Kept separate from the dialog and free of Android types so the taking-apart can be tested, which
 * matters more than it sounds: the shapes vary by engine and by vendor, and the failure mode is
 * silent - a list of identical-looking rows rather than a crash.
 */
object VoiceCatalog {

  /** Suffixes the engine appends to say where synthesis happens; carried separately instead. */
  private val LOCATION_SUFFIXES = listOf("-local", "-network")

  /** What Google's engine calls a locale's unremarkable default voice. */
  private const val PLAIN_DEFAULT = "language"

  /**
   * A readable name for one engine voice name.
   *
   * Not guaranteed unique on its own - two voices can reduce to the same words - which is what
   * [label] is for.
   */
  internal fun describe(voiceName: String): String {
    var core = voiceName
    for (suffix in LOCATION_SUFFIXES) core = core.removeSuffix(suffix)
    core =
      when {
        '#' in core -> core.substringAfter('#')
        "-x-" in core -> core.substringAfterLast("-x-")
        else -> core.substringAfterLast('-')
      }
    if (core.isBlank() || core.equals(PLAIN_DEFAULT, ignoreCase = true)) return "Standard"
    return core
      .replace('_', ' ')
      .replace('-', ' ')
      .split(' ')
      .filter { it.isNotBlank() }
      .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
  }

  /**
   * Readable names for a whole list, made distinguishable.
   *
   * Names that reduce to the same words are numbered. An engine offering three voices all called
   * "Standard" is common, and three identical rows is a picker that cannot be used deliberately -
   * you can only try them one at a time and hope.
   */
  fun label(voiceNames: List<String>): List<String> {
    val described = voiceNames.map(::describe)
    val counts = described.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return described.map { name ->
      if (counts.getValue(name) == 1) {
        name
      } else {
        val index = seen.getOrDefault(name, 0) + 1
        seen[name] = index
        "$name $index"
      }
    }
  }

  /** Rates and pitches outside this range are unintelligible rather than merely fast or deep. */
  val SPEED_RANGE = 0.5f..2.0f

  /** What the engine does with no preference expressed - and what 0 in settings means. */
  const val DEFAULT_SPEED = 1.0f

  /** Reads a stored rate or pitch, treating "never set" and "out of range" as the default. */
  fun speedOrDefault(stored: Float): Float =
    if (stored in SPEED_RANGE) stored else DEFAULT_SPEED
}
