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
import com.lin.agent.core.media.PhotoLibrary
import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemorySource
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.core.models.foregroundTypeFor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Reads the photo library once and files each picture by what is in it.
 *
 * Long, interruptible and resumable, which is why it is a worker rather than a coroutine
 * somewhere. Thousands of photos take real time; the phone will sleep, the user will leave, and
 * a run that starts from the beginning each time never finishes on a library of any size - so
 * anything already indexed is skipped and the next run picks up where this one stopped.
 *
 * Nothing leaves the device and no picture is copied. What is stored is a vector and the
 * content URI the system already has.
 *
 * Dependencies come through a Hilt entry point rather than `@HiltWorker`, deliberately: that
 * needs a custom `WorkerFactory` and a `Configuration.Provider` on the Application, which would
 * change how WorkManager initialises - and model downloading, which took a day to get right,
 * depends on that initialisation.
 */
class PhotoIndexWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun memories(): MemoryStore

    fun embedders(): Embedders
  }

  override suspend fun doWork(): Result =
    try {
      index()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.e(TAG, "Photo indexing failed", e)
      Result.failure(workDataOf(KEY_ERROR to (e.message ?: e::class.java.simpleName)))
    }

  private suspend fun index(): Result {
    val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
    val memories = deps.memories()
    val library = PhotoLibrary(context)

    val already = memories.referencesFor(MemorySource.PHOTO)
    val todo = library.photos().filterNot { it.uri in already }
    if (todo.isEmpty()) return Result.success(workDataOf(KEY_DONE to already.size))

    ensureChannel()
    goForeground(0, todo.size)

    var indexed = 0
    val finished =
      deps.embedders().withEmbedder { embedder ->
        for (photo in todo) {
          // Checked every photo rather than every batch: stopping should feel immediate, and
          // one embedding is the smallest unit of work there is here.
          if (isStopped) break

          val bytes = library.bytesFor(photo.uri)
          if (bytes == null) {
            // A photo that cannot be read is skipped rather than retried. It is usually one the
            // user deleted between listing and reading, and failing the run over it would mean
            // a single bad file blocking the whole library.
            continue
          }

          val vector =
            embedder.embed(listOf(com.lin.agent.engine.EmbedPart.Image(bytes)))
          if (vector.isEmpty()) continue

          memories.remember(
            Memory(
              // No caption: the vector is what finds it, and a description written by a model
              // that cannot be checked would become a fact on the memory screen.
              text = "",
              source = MemorySource.PHOTO,
              reference = photo.uri,
              createdAt = photo.takenAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            ),
            vector,
          )
          indexed++
          if (indexed % REPORT_EVERY == 0) {
            setProgressAsync(workDataOf(KEY_DONE to indexed, KEY_TOTAL to todo.size))
            goForeground(indexed, todo.size)
          }
        }
        true
      }

    runCatching { notificationManager()?.cancel(NOTIFICATION_ID) }

    if (finished == null) {
      return Result.failure(
        workDataOf(KEY_ERROR to "The embedding model is not on this phone, so nothing was indexed.")
      )
    }
    return Result.success(workDataOf(KEY_DONE to indexed, KEY_TOTAL to todo.size))
  }

  private suspend fun goForeground(done: Int, total: Int) {
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Indexing photos")
        .setContentText("$done of $total")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setProgress(total, done, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    val info =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Same check the download worker makes, for the same reason: asking for a type the
        // manifest does not declare throws out of the service and kills the process.
        val permitted =
          foregroundTypeFor(declaredType(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            ?: return
        ForegroundInfo(NOTIFICATION_ID, notification, permitted)
      } else {
        ForegroundInfo(NOTIFICATION_ID, notification)
      }

    try {
      setForeground(info)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.w(TAG, "Could not move indexing to the foreground", e)
    }
  }

  private fun declaredType(): Int =
    runCatching {
        val component =
          android.content.ComponentName(
            context.packageName,
            "androidx.work.impl.foreground.SystemForegroundService",
          )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.packageManager
            .getServiceInfo(component, android.content.pm.PackageManager.ComponentInfoFlags.of(0L))
            .foregroundServiceType
        } else {
          @Suppress("DEPRECATION")
          context.packageManager.getServiceInfo(component, 0).foregroundServiceType
        }
      }
      .getOrDefault(0)

  private fun ensureChannel() {
    val manager = notificationManager() ?: return
    runCatching {
      if (manager.getNotificationChannel(CHANNEL_ID) != null) return
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Photo indexing", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  private fun notificationManager() =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

  companion object {
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"

    private const val TAG = "LinPhotoIndexWorker"
    private const val CHANNEL_ID = "photo_indexing"
    private const val NOTIFICATION_ID = 4202

    /** Often enough to look alive, rarely enough not to spend the run on progress updates. */
    private const val REPORT_EVERY = 10
  }
}
