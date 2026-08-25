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

package com.google.ai.edge.gallery.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.Reminder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "AGReminders"
private const val CHANNEL_ID = "star_reminders"

const val EXTRA_REMINDER_ID = "com.starstoryteller.assistant.REMINDER_ID"

/**
 * Reminders the assistant owns, rather than ones it hands away.
 *
 * `setAlarm` passes an alarm to the clock app, and from that moment it belongs to the clock app: it
 * cannot be listed, changed or cancelled by asking, because there is no API to reach back into it.
 * "Remind me to call the dentist at four" and "what reminders do I have" and "cancel that" only
 * work if the assistant keeps them itself.
 *
 * Kept in DataStore and re-armed on boot. An alarm scheduled with `AlarmManager` does not survive a
 * restart - the pending intents are dropped - so without the boot receiver a reminder set on Monday
 * would simply never arrive if the phone restarted before it was due, silently, which is worse than
 * never having offered reminders.
 */
object ReminderScheduler {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface RepositoryEntryPoint {
    fun dataStoreRepository(): DataStoreRepository
  }

  fun repository(context: Context): DataStoreRepository? =
    try {
      EntryPointAccessors.fromApplication(
          context.applicationContext,
          RepositoryEntryPoint::class.java,
        )
        .dataStoreRepository()
    } catch (e: Throwable) {
      Log.e(TAG, "Settings are not available", e)
      null
    }

  /**
   * Whether exact alarms can be scheduled.
   *
   * From Android 12 an app may be denied the right to schedule an exact alarm, and an inexact one
   * can drift by many minutes - which for "remind me at four" is a wrong answer rather than a late
   * one. So it is checked and reported instead of quietly degrading.
   */
  fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
    return alarmManager.canScheduleExactAlarms()
  }

  /** The system screen where exact alarms are permitted. */
  fun exactAlarmSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(android.net.Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
      Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

  /** Arms the system alarm for one reminder. */
  fun schedule(context: Context, reminder: Reminder) {
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
    val pendingIntent = pendingIntentFor(context, reminder.id)
    try {
      if (canScheduleExact(context)) {
        alarmManager.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          reminder.dueAtMillis,
          pendingIntent,
        )
      } else {
        // Still better than dropping it; the tool tells the user it may be late.
        alarmManager.setAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          reminder.dueAtMillis,
          pendingIntent,
        )
      }
    } catch (e: SecurityException) {
      Log.e(TAG, "Could not schedule reminder ${reminder.id}", e)
    }
  }

  /** Disarms one reminder's alarm. */
  fun cancel(context: Context, id: String) {
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
    alarmManager.cancel(pendingIntentFor(context, id))
  }

  /** Re-arms everything still due. Called on boot, and when the app starts. */
  fun rescheduleAll(context: Context) {
    val repository = repository(context) ?: return
    val now = System.currentTimeMillis()
    val reminders = repository.readReminders().reminderList
    for (reminder in reminders) {
      if (reminder.delivered) continue
      if (reminder.dueAtMillis <= now) {
        // Due while the phone was off. Deliver it now rather than never: a reminder that is late
        // is still worth having, and silently dropping it is the failure this whole class exists
        // to prevent.
        deliver(context, reminder)
      } else {
        schedule(context, reminder)
      }
    }
  }

  /** Posts the reminder's notification and marks it delivered. */
  fun deliver(context: Context, reminder: Reminder) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
      manager?.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
          )
          .apply { description = context.getString(R.string.reminder_channel_description) }
      )
    }

    val openIntent =
      PendingIntent.getActivity(
        context,
        reminder.id.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    manager?.notify(
      reminder.id.hashCode(),
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.reminder_notification_title))
        .setContentText(reminder.text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openIntent)
        .build(),
    )

    val repository = repository(context) ?: return
    val current = repository.readReminders()
    val updated =
      current.toBuilder().clearReminder().apply {
        for (existing in current.reminderList) {
          addReminder(
            if (existing.id == reminder.id) existing.toBuilder().setDelivered(true).build()
            else existing
          )
        }
      }
    repository.saveReminders(updated.build())
  }

  private fun pendingIntentFor(context: Context, id: String): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      id.hashCode(),
      Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_REMINDER_ID, id),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires when a reminder comes due. */
class ReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
    val repository = ReminderScheduler.repository(context) ?: return
    val reminder = repository.readReminders().reminderList.firstOrNull { it.id == id } ?: return
    if (reminder.delivered) return
    ReminderScheduler.deliver(context, reminder)
  }
}

/**
 * Re-arms every pending reminder after a restart.
 *
 * Alarms do not survive a reboot. Without this, a reminder set before a restart would never fire
 * and nothing would say so.
 */
class ReminderBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (
      intent.action != Intent.ACTION_BOOT_COMPLETED &&
        intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }
    Log.d(TAG, "Re-arming reminders after ${intent.action}")
    ReminderScheduler.rescheduleAll(context)
  }
}
