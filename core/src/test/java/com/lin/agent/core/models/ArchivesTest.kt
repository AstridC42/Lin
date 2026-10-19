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

import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivesTest {

  @get:Rule val temp = TemporaryFolder()

  private fun archive(vararg entries: Pair<String, String>): File {
    val file = temp.newFile("pack.tar.bz2")
    TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
      for ((name, body) in entries) {
        val bytes = body.toByteArray()
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
        tar.write(bytes)
        tar.closeArchiveEntry()
      }
    }
    return file
  }

  @Test
  fun `a pack unpacks into its folder, without what is never loaded, and is marked whole`() {
    val into = temp.newFolder("models")
    val pack =
      archive(
        "voice/tokens.txt" to "a b c",
        "voice/model.onnx" to "full",
        "voice/model.int8.onnx" to "small",
        "voice/espeak-ng-data/en_dict" to "dict",
        "voice/test_wavs/0.wav" to "wav",
      )
    val folder = File(into, "voice")
    Archives.unpack(pack, into, folder)
    assertEquals("a b c", File(folder, "tokens.txt").readText())
    assertTrue(File(folder, "model.int8.onnx").exists())
    assertTrue(File(folder, "espeak-ng-data/en_dict").exists())
    assertFalse("the full-precision copy is not used", File(folder, "model.onnx").exists())
    assertFalse(File(folder, "test_wavs/0.wav").exists())
    assertTrue(File(folder, UNPACKED_MARK).exists())
  }

  @Test
  fun `an entry that tries to escape the folder is refused`() {
    val into = temp.newFolder("models")
    try {
      Archives.unpack(archive("../escaped.txt" to "gotcha"), into, File(into, "voice"))
      fail("expected the archive to be refused")
    } catch (expected: IOException) {
      // Refused, as it should be.
    }
    assertFalse(File(temp.root, "escaped.txt").exists())
    assertFalse(File(into, "voice/$UNPACKED_MARK").exists())
  }

  @Test
  fun `only full-precision models with an int8 twin are unused`() {
    assertEquals(
      listOf("base-encoder.onnx", "test_wavs/1.wav"),
      Archives.unused(listOf("base-encoder.onnx", "base-encoder.int8.onnx", "preprocess.onnx", "test_wavs/1.wav", "tokens.txt")),
    )
  }
}
