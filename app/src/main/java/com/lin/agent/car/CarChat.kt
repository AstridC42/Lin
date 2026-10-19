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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.lin.agent.R
import com.lin.agent.core.car.forTheCar
import com.lin.agent.core.history.SavedLine

/**
 * Lin's side of the car conversation: one messaging notification, written the way Android Auto
 * requires before it will show it - MessagingStyle, a reply action carrying a RemoteInput, and
 * a mark-as-read action, both with semantic actions and neither opening a screen.
 *
 * Android Auto announces it, reads it aloud on request ("Hey Google, read my messages", or a
 * tap), and sends the driver's spoken reply back as text to [CarChatReceiver].
 */
object CarChat {

  private const val CHANNEL = "lin-car-chat"
  private const val ID = 45
  const val KEY_REPLY = "car_reply"
  const val ACTION_REPLY = "com.lin.agent.car.REPLY"
  const val ACTION_READ = "com.lin.agent.car.READ"

  /** How many lines of the conversation the car shows. More is only more to scroll past. */
  private const val SHOWN = 6

  /** The first message, so there is something to reply to before anything has been asked. */
  fun greet(context: Context) =
    post(context, listOf(SavedLine(false, context.getString(R.string.car_chat_greeting))), alert = false)

  fun cancel(context: Context) {
    runCatching { NotificationManagerCompat.from(context).cancel(ID) }
  }

  fun isShowing(context: Context): Boolean =
    runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == ID } == true
      }
      .getOrDefault(false)

  /**
   * Shows the conversation. [alert] for a new answer, so the car announces it; quiet for the
   * update straight after a reply, which Android Auto needs to stop showing a spinner.
   */
  fun post(context: Context, lines: List<SavedLine>, alert: Boolean) {
    if (
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    )
      return
    runCatching {
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      if (manager.getNotificationChannel(CHANNEL) == null) {
        manager.createNotificationChannel(
          NotificationChannel(CHANNEL, context.getString(R.string.car_chat_channel), NotificationManager.IMPORTANCE_HIGH)
        )
      }

      val you = Person.Builder().setName(context.getString(R.string.car_chat_you)).build()
      val lin =
        Person.Builder()
          .setName(context.getString(R.string.app_name))
          .setKey("lin")
          .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
          .build()
      val style = NotificationCompat.MessagingStyle(you)
      val now = System.currentTimeMillis()
      val shown = lines.takeLast(SHOWN)
      shown.forEachIndexed { index, line ->
        val at = now - (shown.size - index) * 1000L
        val text = if (line.fromUser) line.text else forTheCar(line.text)
        style.addMessage(NotificationCompat.MessagingStyle.Message(text, at, if (line.fromUser) null else lin))
      }

      val replyIntent =
        PendingIntent.getBroadcast(
          context,
          1,
          Intent(context, CarChatReceiver::class.java).setAction(ACTION_REPLY),
          // Mutable, because the system writes the spoken reply into it. Explicit, so nothing
          // else can receive it.
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
      val reply =
        NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_mic),
            context.getString(R.string.car_chat_reply),
            replyIntent,
          )
          .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.car_chat_reply)).build())
          .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
          .setShowsUserInterface(false)
          .build()
      val read =
        NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_mic),
            context.getString(R.string.car_chat_read),
            PendingIntent.getBroadcast(
              context,
              2,
              Intent(context, CarChatReceiver::class.java).setAction(ACTION_READ),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
          )
          .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
          .setShowsUserInterface(false)
          .build()

      val open =
        PendingIntent.getActivity(
          context,
          3,
          Intent(context, Class.forName("com.lin.agent.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

      manager.notify(
        ID,
        NotificationCompat.Builder(context, CHANNEL)
          .setSmallIcon(R.drawable.ic_notification)
          .setStyle(style)
          .setCategory(NotificationCompat.CATEGORY_MESSAGE)
          .setContentIntent(open)
          .addAction(reply)
          .addInvisibleAction(read)
          .setSilent(!alert)
          .setOnlyAlertOnce(false)
          .build(),
      )
    }
  }
}
