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

package com.lin.agent.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.lin.agent.R
import com.lin.agent.voice.WakeService

/**
 * The notification that starts Lin listening, and why it has to be a notification.
 *
 * A microphone foreground service may not be created from the background at all. This is not
 * the usual background-start rule, which several things exempt an app from - it is the
 * stricter while-in-use rule on top of it, and the documentation is explicit that the general
 * exemptions do not cover it. An app in the background that creates a microphone service gets
 * a SecurityException at creation.
 *
 * What is allowed is a service started by somebody interacting with a notification. So this is
 * the one way Lin can begin listening in a car without the app being opened first: a
 * notification that sits there, and one tap on it before setting off.
 *
 * Deliberately low importance and ongoing: it is a button rather than news, and it should not
 * make a sound every time it appears.
 */
object CarNotification {

  private const val CHANNEL = "lin-car"
  private const val ID = 44

  /** Puts it up, or takes it down. Safe to call with the same value twice. */
  fun show(context: Context, wanted: Boolean) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (!wanted) {
      runCatching { manager.cancel(ID) }
      return
    }

    runCatching {
      if (manager.getNotificationChannel(CHANNEL) == null) {
        manager.createNotificationChannel(
          NotificationChannel(
            CHANNEL,
            context.getString(R.string.car_channel),
            NotificationManager.IMPORTANCE_LOW,
          )
        )
      }

      val listen =
        PendingIntent.getForegroundService(
          context,
          0,
          Intent(context, WakeService::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

      manager.notify(
        ID,
        Notification.Builder(context, CHANNEL)
          .setContentTitle(context.getString(R.string.car_title))
          .setContentText(context.getString(R.string.car_detail))
          .setSmallIcon(R.drawable.ic_mic)
          .setOngoing(true)
          .setContentIntent(listen)
          .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_mic),
                context.getString(R.string.car_listen),
                listen,
              )
              .build()
          )
          .build(),
      )
    }
  }

  /**
   * Whether it is on screen.
   *
   * Asked of the notification manager rather than remembered in a setting, because the
   * question is about what is on screen right now and the answer costs nothing. An app may
   * always read back its own notifications.
   */
  fun isShowing(context: Context): Boolean =
    runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any {
          it.id == ID
        } == true
      }
      .getOrDefault(false)
}
