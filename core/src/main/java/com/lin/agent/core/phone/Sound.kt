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

package com.lin.agent.core.phone

import java.util.Locale

/** Which of the phone's volumes. Each maps to an Android audio stream. */
enum class SoundStream(val label: String) {
  MEDIA("media"),
  RING("ringtone"),
  NOTIFICATION("notification"),
  ALARM("alarm"),
  CALL("call"),
}

/** The phone's ringer: rings, buzzes, or neither. */
enum class RingerMode(val label: String) {
  SOUND("sound"),
  VIBRATE("vibrate"),
  SILENT("silent"),
}

/** What a volume request asks for. */
sealed interface VolumeChange {
  /** An exact level, 0 to 100 percent. */
  data class Percent(val percent: Int) : VolumeChange

  data object Up : VolumeChange

  data object Down : VolumeChange

  data object Mute : VolumeChange

  data object Max : VolumeChange
}

/** "media", "music", "ring", "ringtone"... to a stream. Media when nothing is said. */
fun streamNamed(said: String?): SoundStream? {
  val text = said?.lowercase(Locale.ROOT)?.trim().orEmpty()
  if (text.isEmpty()) return SoundStream.MEDIA
  return when {
    Regex("\\b(media|music|song|video|youtube|spotify|podcast|playback)").containsMatchIn(text) -> SoundStream.MEDIA
    Regex("\\b(ring|ringer|ringtone|calls? ring)").containsMatchIn(text) -> SoundStream.RING
    Regex("\\b(notification|notifications|message|messages|text)").containsMatchIn(text) -> SoundStream.NOTIFICATION
    Regex("\\b(alarm|alarms|clock)").containsMatchIn(text) -> SoundStream.ALARM
    Regex("\\b(call|in-call|in call|voice call|earpiece)").containsMatchIn(text) -> SoundStream.CALL
    else -> null
  }
}

/** "silent", "vibrate", "sound", "normal", "ring"... to a ringer mode. */
fun ringerModeNamed(said: String?): RingerMode? {
  val text = said?.lowercase(Locale.ROOT)?.trim().orEmpty()
  return when {
    text.isEmpty() -> null
    Regex("\\b(silent|silence|mute|muted|quiet)\\b").containsMatchIn(text) -> RingerMode.SILENT
    Regex("\\b(vibrat\\w*|buzz)").containsMatchIn(text) -> RingerMode.VIBRATE
    Regex("\\b(sound|normal|ring|ringer|loud|general|unmute)\\b").containsMatchIn(text) -> RingerMode.SOUND
    else -> null
  }
}

/**
 * "50", "50%", "half", "max", "up", "down", "mute", "a bit louder"... to a change.
 *
 * Null when it is not one, so the tool can say what it accepts rather than guess.
 */
fun volumeChangeFrom(said: String?): VolumeChange? {
  val text = said?.lowercase(Locale.ROOT)?.trim().orEmpty()
  if (text.isEmpty()) return null
  Regex("(\\d{1,3})\\s*(%|percent)?").find(text)?.let { match ->
    val number = match.groupValues[1].toInt()
    // "7" out of a phone's usual 15 steps is meant as a level, but there is no way to know the
    // scale someone has in mind; a bare small number is read as out of ten, like a volume bar.
    val percent = if (match.groupValues[2].isEmpty() && number <= 10 && !text.contains("percent")) number * 10 else number
    return VolumeChange.Percent(percent.coerceIn(0, 100))
  }
  return when {
    Regex("\\b(unmute)\\b").containsMatchIn(text) -> VolumeChange.Percent(UNMUTE_PERCENT)
    Regex("\\b(mute|silent|zero|off|nothing)\\b").containsMatchIn(text) -> VolumeChange.Mute
    Regex("\\b(max|maximum|full|loudest|all the way up|100)\\b").containsMatchIn(text) -> VolumeChange.Max
    Regex("\\b(half|halfway|middle|medium)\\b").containsMatchIn(text) -> VolumeChange.Percent(50)
    Regex("\\b(min|minimum|lowest)\\b").containsMatchIn(text) -> VolumeChange.Percent(1)
    Regex("\\b(up|louder|raise|increase|higher|more)\\b").containsMatchIn(text) -> VolumeChange.Up
    Regex("\\b(down|quieter|softer|lower|decrease|less|reduce)\\b").containsMatchIn(text) -> VolumeChange.Down
    else -> null
  }
}

/**
 * The stream index to set for [change], given where it is now and its range.
 *
 * An exact percentage is honoured as asked - the user said the number. A step up goes by about
 * a tenth, as a volume key does. A non-zero percentage never rounds down to silence.
 */
fun volumeIndexFor(change: VolumeChange, current: Int, min: Int, max: Int): Int {
  if (max <= min) return current
  val span = max - min
  val step = (span / 10).coerceAtLeast(1)
  return when (change) {
    is VolumeChange.Percent ->
      if (change.percent <= 0) min
      else (min + Math.round(span * change.percent / 100.0).toInt()).coerceIn(min + 1, max)
    VolumeChange.Up -> (current + step).coerceAtMost(max)
    VolumeChange.Down -> (current - step).coerceAtLeast(min)
    VolumeChange.Mute -> min
    VolumeChange.Max -> max
  }
}

/** A stream index as the percentage a person would say. */
fun percentOf(index: Int, min: Int, max: Int): Int =
  if (max <= min) 0 else Math.round((index - min) * 100.0 / (max - min)).toInt().coerceIn(0, 100)

/**
 * Plain sound commands that need no model: "silent mode", "vibrate", "volume 50", "mute", "turn
 * the volume up". Narrow on purpose, like the switches: anything with more to it is the model's.
 */
fun soundCommand(text: String): Pair<String, Map<String, Any?>>? {
  val words = text.split(' ').filter { it.isNotBlank() }
  if (words.size > 7) return null

  // Ringer mode: "silent mode", "put the phone on vibrate", "sound mode on", "turn off silent".
  val ringerish = Regex("\\b(silent|vibrat\\w*|sound mode|ring mode|ringer|normal mode)").containsMatchIn(text)
  if (ringerish && !text.contains("volume")) {
    val off = Regex("\\b(off|disable|deactivate|stop)\\b").containsMatchIn(text)
    val mode = ringerModeNamed(text) ?: return null
    val leftover = words.filter { it !in RINGER_WORDS && ringerModeNamed(it) == null }
    if (leftover.isNotEmpty()) return null
    val wanted = if (off && mode != RingerMode.SOUND) RingerMode.SOUND else mode
    return "sound_mode" to mapOf("mode" to wanted.label)
  }

  if (!Regex("\\b(volume|mute|unmute|louder|quieter)\\b").containsMatchIn(text)) return null
  val change = volumeChangeFrom(text.replace("volume", " ")) ?: return null
  val stream = SoundStream.entries.firstOrNull { s -> STREAM_WORDS.getValue(s).any { Regex("\\b$it\\b").containsMatchIn(text) } }
  val known = RINGER_WORDS + VOLUME_WORDS + STREAM_WORDS.values.flatten()
  val leftover = words.filter { w -> w !in known && !Regex("^\\d{1,3}%?$").matches(w) }
  if (leftover.isNotEmpty()) return null
  return "volume" to buildMap {
    put(
      "level",
      when (change) {
        is VolumeChange.Percent -> "${change.percent}%"
        VolumeChange.Up -> "up"
        VolumeChange.Down -> "down"
        VolumeChange.Mute -> "mute"
        VolumeChange.Max -> "max"
      },
    )
    if (stream != null) put("stream", stream.label)
  }
}

private const val UNMUTE_PERCENT = 40

private val RINGER_WORDS =
  setOf("put", "set", "turn", "switch", "make", "change", "the", "my", "phone", "to", "on", "off", "mode", "into",
    "enable", "disable", "activate", "deactivate", "go", "it", "back", "profile", "sound", "ring", "ringer", "normal",
    "in")

private val VOLUME_WORDS =
  setOf("volume", "up", "down", "louder", "quieter", "mute", "unmute", "max", "maximum", "full", "half", "percent",
    "level", "a", "bit", "little", "all", "way", "lower", "raise", "increase", "decrease", "of", "at", "by", "softer",
    "min", "minimum", "zero", "off", "is")

private val STREAM_WORDS =
  mapOf(
    SoundStream.MEDIA to listOf("media", "music"),
    SoundStream.RING to listOf("ringtone", "ring", "ringer"),
    SoundStream.NOTIFICATION to listOf("notification", "notifications"),
    SoundStream.ALARM to listOf("alarm", "alarms"),
    SoundStream.CALL to listOf("call"),
  )
