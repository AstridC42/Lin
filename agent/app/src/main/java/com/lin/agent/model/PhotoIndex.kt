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

package com.lin.agent.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How the photo index is getting on. */
sealed interface IndexState {
  data object Idle : IndexState

  data class Running(val done: Int, val total: Int) : IndexState

  data class Finished(val indexed: Int) : IndexState

  data class Failed(val message: String) : IndexState
}

/** Starting, watching and stopping the photo indexing run. */
@Singleton
class PhotoIndex @Inject constructor(@ApplicationContext private val context: Context) {

  private val work = WorkManager.getInstance(context)

  /**
   * Starts indexing, or leaves a run in progress alone.
   *
   * KEEP here, unlike the model download, and the difference is worth saying. A download that
   * cannot be restarted is a dead button, because there is nothing else that finishes it. This
   * run is resumable by construction - it skips what is already indexed - so a second tap
   * during a run would only start the same work twice, and a run interrupted halfway is
   * continued by the next start rather than needing one.
   */
  fun start() {
    work.enqueueUniqueWork(
      NAME,
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<PhotoIndexWorker>()
        .setConstraints(
          // No network constraint: this is entirely on-device, and requiring a connection to
          // index your own photos would be both wrong and a lie about where they go.
          Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build()
        )
        .build(),
    )
  }

  fun stop() = work.cancelUniqueWork(NAME)

  fun watch(): Flow<IndexState> =
    work.getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
      val info = infos.lastOrNull() ?: return@map IndexState.Idle
      when (info.state) {
        WorkInfo.State.RUNNING ->
          IndexState.Running(
            done = info.progress.getInt(PhotoIndexWorker.KEY_DONE, 0),
            total = info.progress.getInt(PhotoIndexWorker.KEY_TOTAL, 0),
          )
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> IndexState.Running(0, 0)
        WorkInfo.State.SUCCEEDED ->
          IndexState.Finished(info.outputData.getInt(PhotoIndexWorker.KEY_DONE, 0))
        WorkInfo.State.FAILED ->
          IndexState.Failed(
            info.outputData.getString(PhotoIndexWorker.KEY_ERROR) ?: "Indexing did not finish."
          )
        WorkInfo.State.CANCELLED -> IndexState.Idle
      }
    }

  private companion object {
    const val NAME = "photo-index"
  }
}
