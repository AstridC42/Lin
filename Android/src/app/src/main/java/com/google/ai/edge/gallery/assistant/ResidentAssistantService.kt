/*
 * Copyright 2026 The Star Storyteller Authors
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "AGResidentAssistant"
private const val CHANNEL_ID = "star_resident"
private const val NOTIFICATION_ID = 4712

/**
 * Keeps the loaded model in memory after the chat screen is closed.
 *
 * This is the difference between an app you open and an assistant that is simply there. Everything
 * built on top of being summoned - the pop-up when the power button is held, the widget, the car,
 * the wake word - can only answer in place while a model is loaded, and loading one takes tens of
 * seconds and gigabytes. Without this service the first thing any of them did, most of the time,
 * was apologise and open the chat.
 *
 * ### Why a service at all
 *
 * Nothing about closing the chat releases the model: the engine hangs off the `Model` object, which
 * the task holds, which is a singleton - so the model survives the screen and even the activity.
 * What does not survive is the *process*, which Android reclaims at will once nothing in the app is
 * visible. A foreground service is the one supported way to say "this process is still doing
 * something", and that is the whole of what this does. It runs no loop, holds no wake lock and
 * listens to nothing; it exists to be a reason not to be killed.
 *
 * ### The cost, stated
 *
 * It is off by default and has to be turned on deliberately, because it is not free: the model's
 * memory - often two to four gigabytes - stays claimed while it runs, which on a phone with other
 * apps open means some of them get evicted instead. The ongoing notification is required by the
 * platform and is also the right thing to show: something holding that much memory should not do
 * so invisibly, and the Stop action means it never has to be hunted for in settings.
 *
 * Android can still kill a foreground service under real memory pressure, and will. Nothing here
 * prevents that, and nothing here pretends to - the pop-up still falls back to opening the chat
 * when it finds no model loaded, exactly as it did before.
 */
class ResidentAssistantService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      // Turning it off from the notification is also turning the setting off. A service that came
      // back on the next launch after being dismissed would be a service nobody can refuse.
      runCatching {
        val repository = repository(this)
        repository.saveAssistantSettings(
          repository.readAssistantSettings().toBuilder().setKeepModelLoaded(false).build()
        )
      }
      stopSelf()
      return START_NOT_STICKY
    }

    startInForeground()
    // Deliberately not sticky. Being restarted after a kill would put the process back without the
    // model that was the only reason to keep it - an empty process holding a notification.
    return START_NOT_STICKY
  }

  private fun startInForeground() {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
      manager?.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.resident_channel_name),
            // Low: a disclosure that has to stay on screen, not an event worth interrupting for.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = getString(R.string.resident_channel_description) }
      )
    }

    val stopIntent =
      PendingIntent.getService(
        this,
        0,
        Intent(this, ResidentAssistantService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val openIntent =
      PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.resident_notification_title))
        .setContentText(getString(R.string.resident_notification_text))
        .setSmallIcon(R.mipmap.ic_launcher_monochrome)
        .setOngoing(true)
        .setContentIntent(openIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(0, getString(R.string.resident_stop), stopIntent)
        .build()

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      // Android refuses a foreground service started from the background in some states. Going
      // away quietly is right: the assistant works without this, only more slowly.
      Log.w(TAG, "Could not go foreground; stopping", e)
      stopSelf()
    }
  }

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface RepositoryEntryPoint {
    fun dataStoreRepository(): DataStoreRepository
  }

  companion object {
    const val ACTION_STOP = "com.starstoryteller.assistant.STOP_RESIDENT"

    private fun repository(context: Context): DataStoreRepository =
      EntryPointAccessors.fromApplication(
          context.applicationContext,
          RepositoryEntryPoint::class.java,
        )
        .dataStoreRepository()

    /**
     * Starts holding the process, if the user has asked for that.
     *
     * Safe to call repeatedly - starting an already-running foreground service only refreshes its
     * notification - which is what lets the caller simply say this whenever a model finishes
     * loading rather than tracking whether it already did.
     */
    fun startIfEnabled(context: Context) {
      if (!repository(context).readAssistantSettings().keepModelLoaded) return
      runCatching {
        ContextCompat.startForegroundService(
          context,
          Intent(context, ResidentAssistantService::class.java),
        )
      }
    }

    /** Stops holding the process. The loaded model is left alone; only the reason to stay goes. */
    fun stop(context: Context) {
      runCatching { context.stopService(Intent(context, ResidentAssistantService::class.java)) }
    }
  }
}
