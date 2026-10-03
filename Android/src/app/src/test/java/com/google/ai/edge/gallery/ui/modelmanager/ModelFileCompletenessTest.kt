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

package com.google.ai.edge.gallery.ui.modelmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether a downloaded file is actually a model.
 *
 * "Downloaded" used to mean only that the file existed. An interrupted download leaves a fragment,
 * and a gated model whose token has gone leaves an HTML error page saved under the model's own
 * name - both exist, so both counted, and the engine was handed them and reported "Failed to load
 * model from buffer", which tells the user nothing they can act on.
 *
 * The two ways of being wrong here are not symmetric, and that is what sets the tolerance.
 * Accepting a bad file leaves the app unusable behind a message nobody can act on. Rejecting a good
 * one re-downloads gigabytes, over and over. So the bar rejects only what obviously cannot be a
 * model.
 */
class ModelFileCompletenessTest {

  private val gigabyte = 1_000_000_000L

  @Test
  fun acceptsAFullDownload() {
    assertTrue(modelFileLooksComplete(actualBytes = gigabyte, expectedBytes = gigabyte))
  }

  /** The allowlist's size can lag the file it describes, so slightly over or under is fine. */
  @Test
  fun toleratesTheStatedSizeBeingSlightlyOff() {
    assertTrue(modelFileLooksComplete(actualBytes = 950_000_000L, expectedBytes = gigabyte))
    assertTrue(modelFileLooksComplete(actualBytes = 1_100_000_000L, expectedBytes = gigabyte))
  }

  @Test
  fun rejectsATruncatedDownload() {
    assertFalse(modelFileLooksComplete(actualBytes = gigabyte / 2, expectedBytes = gigabyte))
    assertFalse(modelFileLooksComplete(actualBytes = 100_000_000L, expectedBytes = gigabyte))
  }

  /** An error page saved under the model's filename - the case that broke every model at once. */
  @Test
  fun rejectsAnErrorPageSavedAsAModel() {
    assertFalse(modelFileLooksComplete(actualBytes = 4_096L, expectedBytes = gigabyte))
    assertFalse(modelFileLooksComplete(actualBytes = 0L, expectedBytes = gigabyte))
  }

  /** Even with no stated size, a few kilobytes is not a model. */
  @Test
  fun rejectsSomethingTinyEvenWithNoStatedSize() {
    assertFalse(modelFileLooksComplete(actualBytes = 4_096L, expectedBytes = 0L))
    assertFalse(modelFileLooksComplete(actualBytes = 4_096L, expectedBytes = -1L))
  }

  /** With no stated size, anything of a plausible size has to be accepted. */
  @Test
  fun acceptsAPlausibleFileWithNoStatedSize() {
    assertTrue(modelFileLooksComplete(actualBytes = gigabyte, expectedBytes = 0L))
    assertTrue(modelFileLooksComplete(actualBytes = gigabyte, expectedBytes = -1L))
  }
}
