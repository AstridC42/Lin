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

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the UI needs to know about one model's download. */
sealed interface DownloadState {
  data object Idle : DownloadState

  data class Running(val percent: Int, val receivedBytes: Long, val totalBytes: Long) :
    DownloadState

  data object Done : DownloadState

  data class Failed(val message: String) : DownloadState
}

/** Starting, watching and stopping model downloads. */
class ModelDownloads(private val context: Context) {

  private val work = WorkManager.getInstance(context)

  /**
   * Starts downloading [model], or does nothing if it is already running.
   *
   * KEEP rather than REPLACE: tapping the button twice should not restart a download that is
   * already two gigabytes in. Unique by model name, so two different models can download at once
   * if somebody asks for that.
   */
  fun start(model: CatalogModel) {
    work.enqueueUniqueWork(
      nameFor(model),
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<ModelDownloadWorker>()
        .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.name))
        .setConstraints(
          Constraints.Builder()
            // Unmetered only would be the cautious choice and the wrong one: it is the user's
            // allowance and their decision, and a phone that refuses to download on mobile data
            // is useless to somebody without home broadband. Requiring connectivity at all is
            // what stops it failing instantly in a tunnel.
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        )
        .build(),
    )
  }

  fun cancel(model: CatalogModel) = work.cancelUniqueWork(nameFor(model))

  /** Follows [model]'s download. Emits [DownloadState.Idle] when there is nothing in flight. */
  fun watch(model: CatalogModel): Flow<DownloadState> =
    work.getWorkInfosForUniqueWorkFlow(nameFor(model)).map { infos ->
      val info = infos.lastOrNull() ?: return@map DownloadState.Idle
      when (info.state) {
        WorkInfo.State.RUNNING -> {
          val received = info.progress.getLong(ModelDownloadWorker.KEY_RECEIVED, 0)
          val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, model.sizeBytes)
          DownloadState.Running(
            percent = DownloadProgress(received, total).percent,
            receivedBytes = received,
            totalBytes = total,
          )
        }
        // Enqueued counts as running for the user: they tapped the button and something is
        // happening, even if it is waiting for a network.
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> DownloadState.Running(0, 0, model.sizeBytes)
        WorkInfo.State.SUCCEEDED -> DownloadState.Done
        WorkInfo.State.FAILED ->
          DownloadState.Failed(
            info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
              ?: "The download did not finish."
          )
        WorkInfo.State.CANCELLED -> DownloadState.Idle
      }
    }

  private fun nameFor(model: CatalogModel) = "download:${model.name}"
}
