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

  /**
   * Asked for, but not transferring yet - no connection, or storage too low.
   *
   * Separate from [Running] because reporting a queued download as "0%" is a lie the user cannot
   * see past: a progress bar that never moves looks exactly like a download that is broken.
   */
  data object Waiting : DownloadState

  data class Running(val percent: Int, val receivedBytes: Long, val totalBytes: Long) :
    DownloadState

  data object Done : DownloadState

  data class Failed(val message: String) : DownloadState
}

/** Starting, watching and stopping model downloads. */
class ModelDownloads(private val context: Context) {

  private val work = WorkManager.getInstance(context)

  /**
   * Starts or resumes downloading [model].
   *
   * REPLACE, not KEEP. KEEP reads like the careful choice - do not restart a download that is
   * already two gigabytes in - and it was the wrong one, for a reason worth keeping written
   * down: KEEP does nothing at all if a work record for this model exists in any unfinished
   * state, and it does it silently. A process that died mid-download leaves exactly such a
   * record, so the first crash turned Download into a button that could never work again, with
   * no way out of it from inside the app.
   *
   * Replacing costs almost nothing here, because the transfer resumes against the `.part` file
   * already on disk: at most the few megabytes in flight are fetched twice. Set against a button
   * that is permanently dead, that is not a close call.
   *
   * Unique by model name, so two different models can download at once if somebody asks for that.
   */
  fun start(model: CatalogModel) {
    work.enqueueUniqueWork(
      nameFor(model),
      ExistingWorkPolicy.REPLACE,
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

  /**
   * Follows [model]'s download. Emits [DownloadState.Idle] when there is nothing in flight.
   *
   * Every record under the name is considered, not the last one, because `REPLACE` leaves the
   * cancelled record beside the new one and nothing promises which order they arrive in. Reading
   * one of them would mean a running download sometimes reporting itself as idle.
   */
  fun watch(model: CatalogModel): Flow<DownloadState> =
    work.getWorkInfosForUniqueWorkFlow(nameFor(model)).map { infos ->
      mostRelevant(infos.map { stateOf(it, model.build.sizeBytes) })
    }

  private fun stateOf(info: WorkInfo, expectedBytes: Long): DownloadState =
    when (info.state) {
      WorkInfo.State.RUNNING -> {
        val received = info.progress.getLong(ModelDownloadWorker.KEY_RECEIVED, 0)
        val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, expectedBytes)
        DownloadState.Running(
          percent = DownloadProgress(received, total).percent,
          receivedBytes = received,
          totalBytes = total,
        )
      }
      WorkInfo.State.ENQUEUED,
      WorkInfo.State.BLOCKED -> DownloadState.Waiting
      WorkInfo.State.SUCCEEDED -> DownloadState.Done
      WorkInfo.State.FAILED ->
        DownloadState.Failed(
          info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
            ?: "The download did not finish."
        )
      WorkInfo.State.CANCELLED -> DownloadState.Idle
    }

  private fun nameFor(model: CatalogModel) = "download:${model.name}"
}

/**
 * The one state worth showing, out of however many work records share a name.
 *
 * Ordered by how much the user needs to know rather than by recency, so the result does not
 * depend on the order WorkManager happens to return: a transfer in progress beats one waiting to
 * start, a finished download beats a failed earlier attempt, and a cancelled record - which is
 * what replacing a download leaves behind - says nothing at all.
 */
internal fun mostRelevant(states: List<DownloadState>): DownloadState =
  states.filterIsInstance<DownloadState.Running>().maxByOrNull { it.receivedBytes }
    ?: states.firstOrNull { it is DownloadState.Waiting }
    ?: states.firstOrNull { it is DownloadState.Done }
    ?: states.filterIsInstance<DownloadState.Failed>().firstOrNull()
    ?: DownloadState.Idle
