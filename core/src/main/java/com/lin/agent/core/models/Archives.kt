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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Unpacking a model archive - the voice and hearing packs come as `.tar.bz2` - into the models
 * folder, keeping only what is used.
 */
object Archives {

  /**
   * Unpacks [archive] into [into], then removes files that are never loaded, and writes
   * [UNPACKED_MARK] into [folder] last, so a half-unpacked pack is never taken for a whole one.
   *
   * Refuses any entry that would land outside [into] - an archive is data from the internet, and
   * "../" in a name is the classic way to write over somebody else's files.
   */
  fun unpack(archive: File, into: File, folder: File) {
    val root = into.canonicalFile
    archive.inputStream().buffered().use { raw ->
      TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
        while (true) {
          val entry = tar.nextEntry ?: break
          val target = File(root, entry.name).canonicalFile
          if (!target.path.startsWith(root.path + File.separator)) throw IOException("Unsafe path in archive: ${entry.name}")
          if (entry.isDirectory) {
            target.mkdirs()
            continue
          }
          if (!entry.isFile) continue
          target.parentFile?.mkdirs()
          target.outputStream().use { tar.copyTo(it) }
        }
      }
    }
    val names = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toList()
    unused(names).forEach { File(folder, it).delete() }
    File(folder, UNPACKED_MARK).writeText("ok")
  }

  /**
   * Files in a pack that are never loaded: sample recordings, and full-precision models where an
   * int8 copy sits beside them - Whisper's archive carries both, and the int8 one is what runs.
   */
  fun unused(names: List<String>): List<String> {
    val set = names.toSet()
    return names.filter { name ->
      name.startsWith("test_wavs/") ||
        (name.endsWith(".onnx") && !name.endsWith(".int8.onnx") && name.removeSuffix(".onnx") + ".int8.onnx" in set)
    }
  }
}
