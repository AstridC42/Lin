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

import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemorySource
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The backup file itself. */
class BackupArchiveTest {

  @get:Rule val folder = TemporaryFolder()

  private fun stored(text: String) =
    StoredMemory(
      Memory(text = text, source = MemorySource.TOLD, createdAt = 1_760_000_000_000L),
      floatArrayOf(0.5f, -0.25f),
    )

  private fun writeBackup(packs: List<java.io.File> = emptyList()): ByteArray =
    ByteArrayOutputStream()
      .also {
        BackupArchive.write(
          ToPack(
            appVersion = "0.1.0-dev.18",
            memories = listOf(stored("Otto's sister is Mia."), stored("Otto prefers trains.")),
            settings = """{"reasoning":"BRIEF"}""",
            packs = packs,
          ),
          at = 1_760_000_000_000L,
          out = it,
        )
      }
      .toByteArray()

  @Test
  fun everythingSurvivesTheRoundTrip() {
    val pack = folder.newFile("lin-0001-2026-10-14.jsonl")
    pack.writeText("""{"messages":[{"role":"user","content":"hi"}]}""")

    val opened = BackupArchive.read(writeBackup(listOf(pack)).inputStream())!!

    assertEquals(BackupFormat.FORMAT, opened.manifest.format)
    assertEquals("0.1.0-dev.18", opened.manifest.appVersion)
    assertEquals(2, opened.memories.size)
    assertEquals("Otto's sister is Mia.", opened.memories[0].memory.text)
    assertEquals("""{"reasoning":"BRIEF"}""", opened.settings)
    assertEquals(1, opened.packs.size)
    assertTrue(opened.packs.getValue("lin-0001-2026-10-14.jsonl").contains("hi"))
  }

  @Test
  fun theManifestCountsWhatIsActuallyInThere() {
    val pack = folder.newFile("lin-0001-2026-10-14.jsonl").also { it.writeText("{}") }
    val opened = BackupArchive.read(writeBackup(listOf(pack)).inputStream())!!
    assertEquals(2, opened.manifest.memories)
    assertEquals(1, opened.manifest.packs)
  }

  @Test
  fun anEmptyAppStillProducesAReadableBackup() {
    val bytes =
      ByteArrayOutputStream()
        .also {
          BackupArchive.write(ToPack("0.1.0", emptyList(), "{}", emptyList()), 0L, it)
        }
        .toByteArray()
    val opened = BackupArchive.read(bytes.inputStream())!!
    assertTrue(opened.memories.isEmpty())
    assertEquals(0, opened.manifest.memories)
  }

  // ---- things that are not a backup ----

  @Test
  fun somethingThatIsNotAZipIsRefused() {
    assertNull(BackupArchive.read("not a zip".byteInputStream()))
  }

  @Test
  fun aZipWithNoManifestIsRefused() {
    val bytes =
      ByteArrayOutputStream()
        .also {
          ZipOutputStream(it).use { zip ->
            zip.putNextEntry(ZipEntry("something.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
          }
        }
        .toByteArray()
    assertNull(BackupArchive.read(bytes.inputStream()))
  }

  /**
   * The oldest bug in archive handling.
   *
   * A zip entry may be called anything, including `../../databases/memories.db`. A reader
   * that joins that onto a directory writes outside it. Nothing here is written to a path
   * taken from the file, and this pins that: a traversing entry is ignored rather than kept
   * under a name that could later be used as one.
   */
  @Test
  fun anEntryTryingToEscapeTheArchiveIsIgnored() {
    val bytes =
      ByteArrayOutputStream()
        .also {
          ZipOutputStream(it).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
            zip.write(BackupFormat.manifestJson("x", 0L, 0, 0).toByteArray())
            zip.closeEntry()
            for (nasty in
              listOf(
                BackupFormat.PACKS + "../../evil.jsonl",
                BackupFormat.PACKS + "..\\evil.jsonl",
                BackupFormat.PACKS + ".hidden",
                BackupFormat.PACKS + "sub/dir.jsonl",
              )) {
              zip.putNextEntry(ZipEntry(nasty))
              zip.write("payload".toByteArray())
              zip.closeEntry()
            }
          }
        }
        .toByteArray()

    val opened = BackupArchive.read(bytes.inputStream())!!
    assertTrue(opened.packs.keys.toString(), opened.packs.isEmpty())
  }

  @Test
  fun aPlainPackNameIsStillAccepted() {
    val pack = folder.newFile("lin-0002-2026-10-14.jsonl").also { it.writeText("{}") }
    val opened = BackupArchive.read(writeBackup(listOf(pack)).inputStream())!!
    assertEquals(setOf("lin-0002-2026-10-14.jsonl"), opened.packs.keys)
  }

  @Test
  fun aPackThatCannotBeReadIsSkippedRatherThanFailingTheWholeBackup() {
    val good = folder.newFile("lin-0001-2026-10-14.jsonl").also { it.writeText("{}") }
    val missing = java.io.File(folder.root, "gone.jsonl")
    val bytes =
      ByteArrayOutputStream()
        .also {
          BackupArchive.write(ToPack("x", emptyList(), "{}", listOf(good, missing)), 0L, it)
        }
        .toByteArray()
    assertEquals(1, BackupArchive.read(bytes.inputStream())!!.packs.size)
  }
}
