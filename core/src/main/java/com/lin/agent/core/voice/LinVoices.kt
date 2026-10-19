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

/**
 * Lin's own voice and hearing: which files in a downloaded pack are which, and how a reply is cut
 * into pieces that can be spoken while the next is still being made.
 */
object LinVoices {

  /** The files a text-to-speech engine is built from, relative to the pack's folder. */
  data class VoiceFiles(val kokoro: Boolean, val model: String, val tokens: String, val dataDir: String, val voices: String = "")

  /** The files a speech recogniser is built from, relative to the pack's folder. */
  sealed interface HearingFiles {
    data class Moonshine(val preprocess: String, val encode: String, val uncached: String, val cached: String, val tokens: String) : HearingFiles

    data class Whisper(val encoder: String, val decoder: String, val tokens: String) : HearingFiles
  }

  /** Reads a voice pack's files: Kokoro carries voices.bin, Piper does not. Null if it is neither. */
  fun voiceFiles(names: List<String>): VoiceFiles? {
    val tokens = names.firstOrNull { it == "tokens.txt" } ?: return null
    val data = if (names.any { it.startsWith("espeak-ng-data/") }) "espeak-ng-data" else return null
    return if ("voices.bin" in names) {
      val model = preferInt8(names.filter { it.startsWith("model") && it.endsWith(".onnx") }) ?: return null
      VoiceFiles(kokoro = true, model = model, tokens = tokens, dataDir = data, voices = "voices.bin")
    } else {
      val model = preferInt8(names.filter { it.endsWith(".onnx") && !it.contains('/') }) ?: return null
      VoiceFiles(kokoro = false, model = model, tokens = tokens, dataDir = data)
    }
  }

  /** Reads a hearing pack's files: Moonshine or Whisper. Null if it is neither. */
  fun hearingFiles(names: List<String>): HearingFiles? {
    val top = names.filter { !it.contains('/') }
    if (top.any { it.startsWith("preprocess") }) {
      return HearingFiles.Moonshine(
        preprocess = preferInt8(top.filter { it.startsWith("preprocess") && it.endsWith(".onnx") }) ?: return null,
        encode = preferInt8(top.filter { it.startsWith("encode") && it.endsWith(".onnx") }) ?: return null,
        uncached = preferInt8(top.filter { it.startsWith("uncached_decode") && it.endsWith(".onnx") }) ?: return null,
        cached = preferInt8(top.filter { it.startsWith("cached_decode") && it.endsWith(".onnx") }) ?: return null,
        tokens = top.firstOrNull { it == "tokens.txt" } ?: return null,
      )
    }
    val encoder = preferInt8(top.filter { it.contains("encoder") && it.endsWith(".onnx") }) ?: return null
    val decoder = preferInt8(top.filter { it.contains("decoder") && it.endsWith(".onnx") }) ?: return null
    val tokens = top.firstOrNull { it.endsWith("tokens.txt") } ?: return null
    return HearingFiles.Whisper(encoder, decoder, tokens)
  }

  private fun preferInt8(candidates: List<String>): String? =
    candidates.firstOrNull { it.endsWith(".int8.onnx") } ?: candidates.firstOrNull()

  /**
   * A reply cut into sentences to speak one after another, so the first is heard while the rest
   * are still being made. Short pieces are joined to the next - "Yes." on its own is a stutter -
   * and very long ones are cut at a comma or a space.
   */
  fun sentences(text: String, longest: Int = 220, shortest: Int = 24): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
      val piece = current.toString().trim()
      if (piece.isNotEmpty()) pieces += piece
      current.clear()
    }
    for (line in text.split('\n')) {
      for (sentence in SENTENCE.findAll(line).map { it.value.trim() }.filter { it.isNotEmpty() }) {
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
        if (current.length >= shortest) flush()
      }
      if (current.length >= shortest) flush()
    }
    flush()
    return pieces.flatMap { cut(it, longest) }
  }

  private fun cut(piece: String, longest: Int): List<String> {
    if (piece.length <= longest) return listOf(piece)
    val head = piece.take(longest)
    val at = head.lastIndexOf(", ").takeIf { it > longest / 2 }?.plus(1) ?: head.lastIndexOf(' ').takeIf { it > 0 } ?: longest
    return listOf(piece.take(at).trim()) + cut(piece.drop(at).trim(), longest)
  }

  private val SENTENCE = Regex("[^.!?]+[.!?]*")

  /** Kokoro's voices, in the order its voices file holds them. a/b: American/British; f/m. */
  val KOKORO_SPEAKERS =
    listOf(
      "af", "af_bella", "af_nicole", "af_sarah", "af_sky",
      "am_adam", "am_michael", "bf_emma", "bf_isabella", "bm_george", "bm_lewis",
    )

  /** A speaker's name as a person would read it: "af_bella" is "Bella (American)". */
  fun speakerLabel(id: String): String {
    val accent = if (id.startsWith("b")) "British" else "American"
    val name = id.substringAfter('_', "").replaceFirstChar { it.uppercase() }.ifEmpty { "Default" }
    return "$name ($accent)"
  }
}
