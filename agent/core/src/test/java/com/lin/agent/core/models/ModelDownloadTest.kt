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

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download, tested without a network.
 *
 * Worth testing properly rather than by hand, because the failure modes are the expensive kind:
 * these are two-to-three gigabyte files over somebody's mobile allowance, so a resume that
 * silently restarts costs them real money, and a truncated file that gets the final name crashes
 * the engine natively rather than failing politely.
 */
class ModelDownloadTest {

  @get:Rule val folder = TemporaryFolder()

  /** A response that serves [content] from [rangeFrom], as a real server would. */
  private fun server(
    content: ByteArray,
    status: Int? = null,
    ignoreRange: Boolean = false,
  ): (String, Long) -> ModelDownload.Connection = { _, rangeFrom ->
    val from = if (ignoreRange) 0 else rangeFrom.toInt()
    val slice = content.copyOfRange(from.coerceAtMost(content.size), content.size)
    object : ModelDownload.Connection {
      override val status: Int = status ?: if (rangeFrom > 0 && !ignoreRange) 206 else 200
      override val contentLength: Long = slice.size.toLong()
      override val body: InputStream = ByteArrayInputStream(slice)
      override fun close() {}
    }
  }

  @Test
  fun aWholeFileLandsUnderItsRealName() = runTest {
    val content = ByteArray(5000) { (it % 251).toByte() }
    val destination = File(folder.root, "model.litertlm")

    val outcome =
      ModelDownload(server(content)).fetch(destination.path.let { "http://x/f" }.let { it },
        destination = destination, expectedBytes = content.size.toLong())

    assertEquals(DownloadOutcome.Done, outcome)
    assertTrue(destination.exists())
    assertArrayEqualsBytes(content, destination.readBytes())
    // No leftovers: a .part beside the real file would be downloaded again next time.
    assertFalse(File(folder.root, "model.litertlm.part").exists())
  }

  /** The point of the whole design: what already arrived is not fetched twice. */
  @Test
  fun aResumedDownloadOnlyAsksForWhatIsMissing() = runTest {
    val content = ByteArray(5000) { (it % 251).toByte() }
    val destination = File(folder.root, "model.litertlm")
    val part = File(folder.root, "model.litertlm.part")
    part.writeBytes(content.copyOfRange(0, 2000))

    var requestedFrom = -1L
    val outcome =
      ModelDownload({ url, rangeFrom ->
          requestedFrom = rangeFrom
          server(content)(url, rangeFrom)
        })
        .fetch("http://x/f", destination, content.size.toLong())

    assertEquals(DownloadOutcome.Done, outcome)
    assertEquals("should have resumed from where the part file ended", 2000L, requestedFrom)
    assertArrayEqualsBytes(content, destination.readBytes())
  }

  /**
   * A server that ignores the range header must not corrupt the file.
   *
   * It answers 200 with the whole body, so appending it to what is already there would produce a
   * file of the right-ish size made of the first two thousand bytes twice. Starting again is the
   * only correct response.
   */
  @Test
  fun aServerThatIgnoresTheRangeStartsAgainRatherThanAppending() = runTest {
    val content = ByteArray(5000) { (it % 251).toByte() }
    val destination = File(folder.root, "model.litertlm")
    File(folder.root, "model.litertlm.part").writeBytes(content.copyOfRange(0, 2000))

    val outcome =
      ModelDownload(server(content, status = 200, ignoreRange = true))
        .fetch("http://x/f", destination, content.size.toLong())

    assertEquals(DownloadOutcome.Done, outcome)
    assertArrayEqualsBytes(content, destination.readBytes())
  }

  /**
   * A file that arrives the wrong length never gets the real name.
   *
   * This is the case that matters most: a truncated body, or a captive portal's login page served
   * with a 200, handed to the engine as a model is a native crash rather than an error message.
   */
  @Test
  fun aTruncatedFileIsRejectedAndNotRenamed() = runTest {
    val destination = File(folder.root, "model.litertlm")
    val outcome =
      ModelDownload(server(ByteArray(100)))
        .fetch("http://x/f", destination, expectedBytes = 5000)

    assertTrue(outcome is DownloadOutcome.Failed)
    assertTrue((outcome as DownloadOutcome.Failed).retryable)
    assertFalse("a short file must not get the name the engine loads", destination.exists())
    // What did arrive is kept, so resuming is still possible.
    assertTrue(File(folder.root, "model.litertlm.part").exists())
  }

  @Test
  fun aPartFileLongerThanTheWholeModelIsDiscarded() = runTest {
    val content = ByteArray(1000) { 7 }
    val destination = File(folder.root, "model.litertlm")
    // A leftover from a different, larger version of this model.
    File(folder.root, "model.litertlm.part").writeBytes(ByteArray(9000))

    var requestedFrom = -1L
    val outcome =
      ModelDownload({ url, rangeFrom ->
          requestedFrom = rangeFrom
          server(content)(url, rangeFrom)
        })
        .fetch("http://x/f", destination, content.size.toLong())

    assertEquals(DownloadOutcome.Done, outcome)
    assertEquals("should start from nothing, not resume past the end", 0L, requestedFrom)
    assertEquals(1000, destination.length())
  }

  @Test
  fun aMissingFileIsNotWorthRetrying() = runTest {
    val outcome =
      ModelDownload(server(ByteArray(0), status = 404))
        .fetch("http://x/f", File(folder.root, "m"), expectedBytes = 10)
    assertTrue(outcome is DownloadOutcome.Failed)
    assertFalse("a 404 will still be a 404", (outcome as DownloadOutcome.Failed).retryable)
  }

  @Test
  fun aServerProblemIsWorthRetrying() = runTest {
    val outcome =
      ModelDownload(server(ByteArray(0), status = 503))
        .fetch("http://x/f", File(folder.root, "m"), expectedBytes = 10)
    assertTrue((outcome as DownloadOutcome.Failed).retryable)
  }

  @Test
  fun progressIsReportedAndEndsAtTheTotal() = runTest {
    val content = ByteArray(6_000_000) { 1 }
    val seen = mutableListOf<DownloadProgress>()
    ModelDownload(server(content))
      .fetch("http://x/f", File(folder.root, "m"), content.size.toLong()) { seen.add(it) }

    assertTrue("should report more than once for a large file", seen.size > 2)
    assertEquals(content.size.toLong(), seen.last().receivedBytes)
    assertEquals(100, seen.last().percent)
    // Monotonic: a progress bar that goes backwards reads as a restart.
    assertEquals(seen.map { it.receivedBytes }.sorted(), seen.map { it.receivedBytes })
  }

  @Test
  fun theUrlPointsAtTheRightFileForThisChip() {
    val url = ModelDownload.urlFor(ModelCatalog.EMBEDDING_GEMMA_2, socModel = "SM8750")
    assertEquals(
      "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/" +
        "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm",
      url,
    )
  }

  private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
    assertEquals("length", expected.size, actual.size)
    assertTrue("contents differ", expected.contentEquals(actual))
  }
}
