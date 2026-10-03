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

package com.google.ai.edge.gallery.assistant

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import kotlin.time.Duration
import kotlin.time.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The marker that makes a fatal model load recoverable.
 *
 * Getting this wrong is not a small bug in either direction. Left set when it should be cleared,
 * every launch shows the recovery screen and the assistant never opens on its own. Cleared when it
 * should be set, a model that kills the process on load does so again on the next launch, and the
 * app cannot be opened at all - which is the failure this whole mechanism exists to prevent, and
 * one that cannot be reproduced on a device without bricking the install.
 */
class AssistantLoadingMarkerTest {

  @Test
  fun recordsTheModelWhileItCouldStillBeLoading() {
    assertEquals(
      "Gemma 3n E4B",
      loadingMarkerFor(
        modelName = "Gemma 3n E4B",
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        initializationStatus = null,
      ),
    )
  }

  @Test
  fun keepsTheModelWhileInitializationIsInProgress() {
    assertEquals(
      "Gemma 3n E4B",
      loadingMarkerFor(
        modelName = "Gemma 3n E4B",
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        initializationStatus =
          Model.InitializationStatus.Initializing(TimeSource.Monotonic.markNow()),
      ),
    )
  }

  @Test
  fun clearsOnceInitialized() {
    assertEquals(
      "",
      loadingMarkerFor(
        modelName = "Gemma 3n E4B",
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        initializationStatus =
          Model.InitializationStatus.Initialized(instance = Any(), duration = Duration.ZERO),
      ),
    )
  }

  /** An error the app lived to report is not a launch the app failed to survive. */
  @Test
  fun clearsOnAReportedError() {
    assertEquals(
      "",
      loadingMarkerFor(
        modelName = "Gemma 3n E4B",
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        initializationStatus =
          Model.InitializationStatus.Failed(
            error = IllegalStateException("too large"),
            duration = Duration.ZERO,
          ),
      ),
    )
  }

  @Test
  fun recordsNothingForAModelThatIsNotDownloaded() {
    for (status in
      listOf(
        null,
        ModelDownloadStatusType.NOT_DOWNLOADED,
        ModelDownloadStatusType.IN_PROGRESS,
        ModelDownloadStatusType.FAILED,
      )) {
      assertEquals(
        "no load can be in flight for download status $status",
        "",
        loadingMarkerFor(
          modelName = "Gemma 3n E4B",
          downloadStatus = status,
          initializationStatus = null,
        ),
      )
    }
  }

  @Test
  fun recordsNothingWhenNoModelIsSelected() {
    assertEquals(
      "",
      loadingMarkerFor(
        modelName = "",
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        initializationStatus = null,
      ),
    )
  }
}
