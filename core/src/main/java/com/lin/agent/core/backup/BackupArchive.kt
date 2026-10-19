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

package com.lin.agent.core.backup

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What came out of a backup file. */
data class Unpacked(
  val manifest: Manifest,
  val memories: List<StoredMemory>,
  val settings: String,
  /** Training packs, by their original file name. */
  val packs: Map<String, String>,
)

/** What a backup is made of. */
data class ToPack(
  val appVersion: String,
  val memories: List<StoredMemory>,
  val settings: String,
  val packs: List<File>,
)

/**
 * Reading and writing the backup file.
 *
 * A zip of JSON, which is dull on purpose: somebody opening this in a year, possibly after the
 * app has been rebuilt or renamed, should be able to see what is in it without this code. The
 * models are not in here - they are gigabytes and re-downloadable - and neither is the photo
 * index, which is derived from photos that are still on the phone.
 */
object BackupArchive {

  fun write(what: ToPack, at: Long, out: OutputStream): Int {
    var packed = 0
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
      zip.write(
        BackupFormat.manifestJson(what.appVersion, at, what.memories.size, what.packs.size)
          .toByteArray()
      )
      zip.closeEntry()

      zip.putNextEntry(ZipEntry(BackupFormat.MEMORIES))
      zip.write(BackupFormat.memoriesJson(what.memories).toByteArray())
      zip.closeEntry()

      zip.putNextEntry(ZipEntry(BackupFormat.SETTINGS))
      zip.write(what.settings.toByteArray())
      zip.closeEntry()

      for (pack in what.packs) {
        val text = runCatching { pack.readText() }.getOrNull() ?: continue
        zip.putNextEntry(ZipEntry(BackupFormat.PACKS + pack.name))
        zip.write(text.toByteArray())
        zip.closeEntry()
        packed++
      }
    }
    return packed
  }

  /**
   * Opens a backup, or null if it is not one this build can read.
   *
   * Entry names are checked rather than trusted. A zip entry may be called
   * `../../databases/memories.db`, and a reader that joins that onto a directory writes
   * outside it - the oldest bug in archive handling. Nothing here is written to a path taken
   * from the file: entries are matched against names this app wrote, and anything else is
   * ignored.
   */
  fun read(input: InputStream): Unpacked? {
    var manifest: Manifest? = null
    var memories = emptyList<StoredMemory>()
    var settings = ""
    val packs = mutableMapOf<String, String>()

    runCatching {
        ZipInputStream(input).use { zip ->
          while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            val text = zip.readBytes().decodeToString()
            when {
              name == BackupFormat.MANIFEST -> manifest = BackupFormat.readManifest(text)
              name == BackupFormat.MEMORIES -> memories = BackupFormat.readMemories(text)
              name == BackupFormat.SETTINGS -> settings = text
              name.startsWith(BackupFormat.PACKS) -> {
                // Only the last segment, and only if it is a plain name. A pack entry called
                // "training/../../x" must not become a file called x anywhere.
                val leaf = name.removePrefix(BackupFormat.PACKS)
                if (leaf.isNotEmpty() && !leaf.contains('/') && !leaf.contains('\\') &&
                  !leaf.startsWith(".")
                ) {
                  packs[leaf] = text
                }
              }
            }
          }
        }
      }
      .onFailure { Log.w(TAG, "Could not read the backup", it) }

    val found = manifest ?: return null
    return Unpacked(found, memories, settings, packs)
  }

  private const val TAG = "LinBackupArchive"
}
