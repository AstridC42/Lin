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
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lin.agent.core.diag.CrashReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
 *
 * Everything here that touches the notification or the foreground service is written so that it
 * cannot take the download down with it. That is not defensive habit: going foreground is exactly
 * what crashed this app the first time somebody pressed Download, and the crash was in the
 * platform's own `startForeground`, where no amount of care inside the download loop would have
 * helped. See [foregroundTypeFor].
 */
class ModelDownloadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
    try {
      download()
    } catch (e: CancellationException) {
      // Stopped or cancelled. The part file stays, which is what makes resuming free.
      throw e
    } catch (e: Throwable) {
      // A worker that throws is recorded as failed with no output, and the settings screen then
      // shows "The download did not finish", which tells the user nothing they can act on. So
      // anything unexpected is turned into a sentence and carried out in the work's output, and
      // also written where it can be read off the phone without a computer.
      Log.e(TAG, "Download worker failed", e)
      val modelName = inputData.getString(KEY_MODEL).orEmpty()
      runCatching {
        CrashReport.note(
          context,
          key = "download-$modelName",
          text = "Downloading $modelName stopped with an error: ${describe(e)}",
        )
      }
      Result.failure(workDataOf(KEY_MODEL to modelName, KEY_ERROR to describe(e)))
    }

  private suspend fun download(): Result {
    val modelName = inputData.getString(KEY_MODEL) ?: return Result.failure()
    val model = ModelCatalog.byName(modelName) ?: return Result.failure()
    val store = ModelStore(context)

    if (store.isPresent(model)) return Result.success()

    // Before anything is built against it, let alone posted. A notification whose channel does
    // not exist yet is dropped on the floor on API 26+.
    ensureChannel()
    goForeground(model.name, 0)

    // Before asking for gigabytes of room, give back any that a download this app can no longer
    // reach is still holding.
    val freed = store.clearStrayParts()
    if (freed > 0) Log.i(TAG, "Freed $freed bytes of unreachable partial downloads")

    // One value for both the address and the length to check it against. See ModelBuild.
    val build = model.build
    // Each phone's own token, if one was entered. See SettingsRepository.hfToken.
    val token =
      runCatching { com.lin.agent.core.settings.SettingsRepository(context).hfToken.first() }
        .getOrDefault("")
    val outcome =
      ModelDownload.withToken(token)
        .fetch(
          url = ModelDownload.urlFor(model.repoId, build),
          destination = store.fileFor(model),
          expectedBytes = build.sizeBytes,
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

    // In both branches, because a failure that leaves "Downloading... 37%" pinned to the shade
    // for the rest of the day is its own bug.
    runCatching { notificationManager()?.cancel(NOTIFICATION_ID) }

    return when (outcome) {
      is DownloadOutcome.Done -> Result.success()
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

  /**
   * Asks to keep this download alive while the app is in the background, and carries on if it
   * cannot.
   *
   * Three things can refuse, and none of them is a reason to abandon a download the user asked
   * for: the manifest may not permit the service type (handled in [foregroundInfo]), the system
   * may refuse to start a foreground service at all, and the worker may already have been
   * stopped by the time this runs. Without the service the download still works - it just dies
   * when the app is backgrounded, which is worth saying out loud rather than discovering.
   */
  private suspend fun goForeground(modelName: String, percent: Int) {
    val info = foregroundInfo(modelName, percent)
    if (info == null) {
      Log.w(TAG, "No usable foreground service type; downloading in the background instead")
      runCatching {
        CrashReport.note(
          context,
          key = "download-foreground",
          text =
            "Downloads cannot run as a foreground service on this build, so leaving the app may " +
              "stop one. Whatever has arrived is kept, and pressing Download again resumes it.",
        )
      }
      return
    }
    try {
      setForeground(info)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.w(TAG, "Could not move the download to the foreground", e)
    }
  }

  /**
   * The foreground service's own description of itself, or null when asking would be fatal.
   *
   * Non-null is the normal answer. Null means the manifest and this code disagree about what the
   * download service is allowed to do, and the only safe response is not to ask.
   */
  private fun foregroundInfo(modelName: String, percent: Int): ForegroundInfo? {
    val notification = buildNotification(modelName, percent)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // Before API 34 a foreground service needs no declared type, so there is nothing to get
      // wrong and nothing to check.
      return ForegroundInfo(NOTIFICATION_ID, notification)
    }
    val permitted = foregroundTypeFor(declaredForegroundServiceType(), WANTED_SERVICE_TYPE)
    return permitted?.let { ForegroundInfo(NOTIFICATION_ID, notification, it) }
  }

  /**
   * What the merged manifest says WorkManager's foreground service may do.
   *
   * Read rather than assumed. The declaration lives in the app module's manifest and the code
   * that depends on it lives here, which is exactly the kind of gap that compiles cleanly and
   * crashes on a phone - so this asks the package manager instead of trusting that the two were
   * kept in step. Referenced by name because the class is WorkManager's internal API; a name
   * that stops resolving reads as "no type declared", which is the safe answer.
   */
  private fun declaredForegroundServiceType(): Int =
    runCatching {
        val component = ComponentName(context.packageName, WORK_FOREGROUND_SERVICE)
        val info =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
              component,
              PackageManager.ComponentInfoFlags.of(0L),
            )
          } else {
            @Suppress("DEPRECATION") context.packageManager.getServiceInfo(component, 0)
          }
        info.foregroundServiceType
      }
      .getOrElse { error ->
        Log.w(TAG, "Could not read the foreground service declaration", error)
        0
      }

  /**
   * Only for expedited work, which this never requests.
   *
   * Kept because the base class's version throws, and a throw from a path nobody walks is still
   * worse than an answer. It reuses the checked one above and falls back to an untyped
   * notification, which is the least-bad answer for a call that cannot happen.
   */
  override suspend fun getForegroundInfo(): ForegroundInfo {
    val modelName = inputData.getString(KEY_MODEL).orEmpty()
    return foregroundInfo(modelName, 0)
      ?: ForegroundInfo(NOTIFICATION_ID, buildNotification(modelName, 0))
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

  private fun ensureChannel() {
    val manager = notificationManager() ?: return
    runCatching {
      if (manager.getNotificationChannel(CHANNEL_ID) != null) return
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  private fun notificationManager() =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

  /** The exception's own words, kept short enough to fit in a work's output data. */
  private fun describe(error: Throwable): String {
    val name = error::class.java.simpleName
    val message = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: return name
    return "$name: ${message.take(200)}"
  }

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

    /**
     * There is no "download" service type. `dataSync` is the one that covers a long transfer the
     * user started, and it is what the app module's manifest declares.
     */
    private const val WANTED_SERVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    /** WorkManager's own service: the thing that actually calls `startForeground`. */
    private const val WORK_FOREGROUND_SERVICE =
      "androidx.work.impl.foreground.SystemForegroundService"
  }
}

/**
 * Which foreground service type a download may ask for, given what the manifest declares.
 *
 * This is the rule that one line of manifest got wrong, and it is worth stating plainly because
 * the failure is so much worse than it sounds. On API 34+, `startForeground(id, notification,
 * type)` throws if `type` is not a subset of the type declared for that service in the manifest:
 *
 *     IllegalArgumentException: foregroundServiceType 0x00000001 is not a subset of
 *     foregroundServiceType attribute 0x00000000 in service element of manifest file
 *
 * The call is made by WorkManager's own service, on the main thread, and WorkManager guards it
 * against `ForegroundServiceStartNotAllowedException` and `SecurityException` only - so this
 * exception escapes and kills the process. Not the worker, not the download: the app. Pressing
 * Download closed the app, and because the work record was left in a state WorkManager never
 * finished, every tap afterwards did nothing.
 *
 * So the type is intersected with what is actually declared, and if nothing survives, the answer
 * is null: do not ask. A download that cannot survive being backgrounded is a real loss, and a
 * long way short of an app that cannot be used.
 *
 * Null rather than zero on purpose. Zero is not a safe thing to pass either - on API 34+ going
 * foreground with no type at all throws `MissingForegroundServiceTypeException`, which WorkManager
 * does not catch either, so "ask for nothing" is the same crash by another name.
 */
fun foregroundTypeFor(declaredInManifest: Int, wanted: Int): Int? =
  (declaredInManifest and wanted).takeIf { it != 0 }
