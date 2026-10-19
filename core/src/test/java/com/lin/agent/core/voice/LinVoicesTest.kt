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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinVoicesTest {

  // The real layouts, as listed from sherpa-onnx's archives after unpacking.
  private val kokoro = listOf("tokens.txt", "model.int8.onnx", "voices.bin", "espeak-ng-data/en_dict", "README.md", "LICENSE")
  private val piper = listOf("en_US-amy-medium.onnx", "en_US-amy-medium.onnx.json", "tokens.txt", "MODEL_CARD", "espeak-ng-data/en_dict")
  private val moonshine =
    listOf("cached_decode.int8.onnx", "uncached_decode.int8.onnx", "tokens.txt", "preprocess.onnx", "encode.int8.onnx", "README.md")
  private val whisper = listOf("base-decoder.int8.onnx", "base-encoder.int8.onnx", "base-tokens.txt")

  @Test
  fun `voice packs are read for what they are`() {
    assertEquals(LinVoices.VoiceFiles(true, "model.int8.onnx", "tokens.txt", "espeak-ng-data", "voices.bin"), LinVoices.voiceFiles(kokoro))
    assertEquals(LinVoices.VoiceFiles(false, "en_US-amy-medium.onnx", "tokens.txt", "espeak-ng-data"), LinVoices.voiceFiles(piper))
    assertNull(LinVoices.voiceFiles(listOf("tokens.txt")))
  }

  @Test
  fun `hearing packs are read for what they are`() {
    assertEquals(
      LinVoices.HearingFiles.Moonshine("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"),
      LinVoices.hearingFiles(moonshine),
    )
    assertEquals(LinVoices.HearingFiles.Whisper("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"), LinVoices.hearingFiles(whisper))
    assertNull(LinVoices.hearingFiles(listOf("README.md")))
  }

  @Test
  fun `a reply is spoken sentence by sentence, short ones joined`() {
    assertEquals(
      listOf("Yes. The flashlight is on now.", "It will be sunny today, around 24 degrees."),
      LinVoices.sentences("Yes. The flashlight is on now. It will be sunny today, around 24 degrees."),
    )
    assertEquals(listOf("One line.", "Another line here."), LinVoices.sentences("One line.\nAnother line here.", shortest = 5))
    assertEquals(emptyList<String>(), LinVoices.sentences("  "))
  }

  @Test
  fun `a very long sentence is cut where it can breathe`() {
    val long = "word ".repeat(80).trim() + "."
    val pieces = LinVoices.sentences(long, longest = 100)
    assertTrue(pieces.all { it.length <= 100 })
    assertEquals(long.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
  }

  @Test
  fun `speakers have names a person can read`() {
    assertEquals(11, LinVoices.KOKORO_SPEAKERS.size)
    assertEquals("Bella (American)", LinVoices.speakerLabel("af_bella"))
    assertEquals("George (British)", LinVoices.speakerLabel("bm_george"))
    assertEquals("Default (American)", LinVoices.speakerLabel("af"))
  }
}
