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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Downloads a model while the user gets on with something else.
 *
 * A foreground worker rather than a coroutine in a view model, because these files are gigabytes
 * and nobody watches a progress bar for twenty minutes. The moment the app is backgrounded,
 * ordinary work is subject to being killed; a foreground worker with a notification is the
 * supported way to keep a long transfer alive, and the notification is also the only place the
 * user can see it is still going.
 *
 * WorkManager rather than a bare service for one specific reason: it survives process death. If
 * the app is killed mid-download, the work is still enqueued and resumes - against a `.part` file
 * that is still on disk, so nothing already transferred is spent twice.
 */
class ModelDownloadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val modelName = inputData.getString(KEY_MODEL) ?: return Result.failure()
    val model = ModelCatalog.byName(modelName) ?: return Result.failure()
    val store = ModelStore(context)

    if (store.isPresent(model)) return Result.success()

    setForeground(foregroundInfo(model.name, 0))

    val outcome =
      ModelDownload()
        .fetch(
          url = ModelDownload.urlFor(model),
          destination = store.fileFor(model),
          expectedBytes = model.sizeBytes,
          onProgress = { progress ->
            setProgressAsync(
              workDataOf(
                KEY_MODEL to model.name,
                KEY_RECEIVED to progress.receivedBytes,
                KEY_TOTAL to progress.totalBytes,
              )
            )
            // Best-effort: the notification is a courtesy and an exception from it must not fail
            // a download that is otherwise working.
            runCatching {
              notificationManager()?.notify(
                NOTIFICATION_ID,
                buildNotification(model.name, progress.percent),
              )
            }
          },
        )

    return when (outcome) {
      is DownloadOutcome.Done -> {
        runCatching { notificationManager()?.cancel(NOTIFICATION_ID) }
        Result.success()
      }
      is DownloadOutcome.Failed -> {
        Log.w(TAG, "Download failed: ${outcome.message}")
        val data = workDataOf(KEY_MODEL to model.name, KEY_ERROR to outcome.message)
        // Retried only where retrying could help. A 404 retried ten times is ten times the
        // battery for the same answer.
        if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry()
        else Result.failure(data)
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    foregroundInfo(inputData.getString(KEY_MODEL).orEmpty(), 0)

  private fun foregroundInfo(modelName: String, percent: Int): ForegroundInfo {
    val notification = buildNotification(modelName, percent)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIFICATION_ID, notification)
    }
  }

  private fun buildNotification(modelName: String, percent: Int) =
    NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle("Downloading $modelName")
      .setContentText("$percent%")
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setProgress(100, percent, percent == 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
      .also { ensureChannel() }

  private fun ensureChannel() {
    val manager = notificationManager() ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun notificationManager() =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

  companion object {
    const val KEY_MODEL = "model"
    const val KEY_RECEIVED = "received"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"

    private const val TAG = "LinModelDownloadWorker"
    private const val CHANNEL_ID = "model_downloads"
    private const val NOTIFICATION_ID = 4201

    /** Enough to ride out a tunnel or a lift, not enough to grind all night on a dead URL. */
    private const val MAX_ATTEMPTS = 5
  }
}
