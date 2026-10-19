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

package com.lin.agent.remind

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lin.agent.R
import com.lin.agent.core.remind.Reminder
import com.lin.agent.core.remind.dueReminders
import com.lin.agent.core.remind.pendingReminders
import com.lin.agent.core.remind.reminderId
import com.lin.agent.core.remind.remindersFrom
import com.lin.agent.core.remind.storedReminders
import com.lin.agent.core.remind.withReminder
import com.lin.agent.core.remind.withoutReminder
import com.lin.agent.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Reminders Lin can actually give.
 *
 * Lin could already open the clock app with an alarm half filled in, which is not a reminder -
 * it is a request that somebody else set one. This is the other thing: a moment, some words,
 * and a notification that arrives whether or not the app is running.
 *
 * Three pieces, and all three are needed. The moment goes to [AlarmManager], which is the only
 * thing on the phone that will wake it. The words go to the settings, because an alarm is lost
 * on reboot and a reminder that a restart quietly cancels is worse than one that was never set.
 * And the firing goes through [ReminderReceiver], which reads the words back out of the
 * settings rather than trusting what the alarm carries - so a reminder cancelled an hour ago
 * stays cancelled even if its alarm was already in flight.
 */
@Singleton
class ReminderBook
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val settings: SettingsRepository,
) {

  /** Every reminder still stored, soonest first. */
  suspend fun all(): List<Reminder> = remindersFrom(settings.reminders.first())

  /** The ones still ahead, which are the only ones worth reading out. */
  suspend fun ahead(nowMillis: Long = System.currentTimeMillis()): List<Reminder> =
    pendingReminders(all(), nowMillis)

  /**
   * Stores a reminder and arms it.
   *
   * Stored first. If arming fails - and it can, there is a system-wide cap on alarms - the
   * reminder is still there to be armed again at the next start, which is a late notification
   * rather than none.
   */
  suspend fun set(text: String, atMillis: Long): Reminder {
    val reminder = Reminder(id = reminderId(atMillis, text), text = text.trim(), atMillis = atMillis)
    settings.setReminders(storedReminders(withReminder(all(), reminder)))
    arm(reminder)
    return reminder
  }

  /** Forgets a reminder and takes its alarm down. Returns false when there was nothing to forget. */
  suspend fun cancel(id: String): Boolean {
    val before = all()
    val after = withoutReminder(before, id)
    if (after.size == before.size) return false
    settings.setReminders(storedReminders(after))
    alarms()?.cancel(intentFor(id))
    return true
  }

  /**
   * Delivers one reminder, if it is still a reminder.
   *
   * The id is read back out of storage rather than taken from the alarm. An alarm that has
   * already been handed to the system cannot always be recalled, so "was it cancelled?" has to
   * be asked at the moment it fires, not before.
   */
  suspend fun fire(id: String) {
    val reminder = all().firstOrNull { it.id == id } ?: return
    notify(reminder)
    settings.setReminders(storedReminders(withoutReminder(all(), id)))
  }

  /**
   * Puts every stored reminder back on the clock, after a reboot or an update.
   *
   * Alarms do not survive either, so without this the words would still be in the settings and
   * nothing would ever go off - the worst of the three possible failures, because the person
   * would have no way of telling.
   *
   * One that came due while the phone was off is delivered late if it is less than three hours
   * late, and dropped otherwise. The rule is the one the morning routines use: a reminder from
   * last Tuesday arriving now is not a late reminder, it is a wrong one.
   */
  suspend fun rearm(nowMillis: Long = System.currentTimeMillis()) {
    val stored = all()
    if (stored.isEmpty()) return
    val missed = dueReminders(stored, nowMillis)
    var kept = stored
    for (reminder in missed) {
      if (nowMillis - reminder.atMillis <= CATCH_UP_MILLIS) notify(reminder)
      kept = withoutReminder(kept, reminder.id)
    }
    if (kept.size != stored.size) settings.setReminders(storedReminders(kept))
    pendingReminders(kept, nowMillis).forEach(::arm)
  }

  /**
   * Whether Lin may set an alarm for the exact minute.
   *
   * Android 12 made this a permission, and refusing it is a reasonable thing for somebody to
   * do. So this is not a gate: without it a reminder is set anyway, inexactly, and the tool
   * says the notification may be a few minutes late. Refusing a reminder outright over a
   * permission about punctuality would be the app being precious.
   */
  fun exactAllowed(): Boolean = alarms()?.canScheduleExactAlarms() == true

  /** Where somebody goes to allow exact alarms, or null if this phone has no such screen. */
  fun exactPermissionIntent(): Intent? =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
      .takeIf {
        it.resolveActivity(context.packageManager) != null ||
          Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).resolveActivity(context.packageManager) != null
      }

  private fun arm(reminder: Reminder) {
    val manager = alarms() ?: return
    val intent = intentFor(reminder.id)
    try {
      if (manager.canScheduleExactAlarms()) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, intent)
      } else {
        // Inexact, and allowed while the phone is dozing. Android may hold it back by a few
        // minutes; being a little late is the whole cost of not having the permission.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, intent)
      }
    } catch (refused: SecurityException) {
      // The permission can be revoked between the check and the call. The reminder stays
      // stored, so the next start arms it inexactly.
      Log.w(TAG, "Not allowed to set an alarm for ${reminder.id}", refused)
    }
  }

  private fun intentFor(id: String): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      id.hashCode(),
      Intent(context, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION_REMIND)
        // In the data rather than only an extra: two PendingIntents that differ only by their
        // extras are the same PendingIntent, so a second reminder would replace the first.
        .setData(Uri.parse("lin://reminder/$id"))
        .putExtra(ReminderReceiver.EXTRA_ID, id),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun notify(reminder: Reminder) {
    if (
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(TAG, "No notification permission, so ${reminder.id} has nowhere to go")
      return
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      NotificationChannel(
          CHANNEL,
          context.getString(R.string.reminder_channel),
          // High, with a sound. A reminder nobody notices is not a reminder, and this is the
          // one notification the person asked for by name.
          NotificationManager.IMPORTANCE_HIGH,
        )
        .apply { description = context.getString(R.string.reminder_channel_detail) }
    )

    val open =
      PendingIntent.getActivity(
        context,
        reminder.id.hashCode(),
        Intent(context, Class.forName("com.lin.agent.MainActivity"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    manager.notify(
      reminder.id.hashCode(),
      NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(reminder.text)
        .setContentText(context.getString(R.string.reminder_from_lin))
        .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build(),
    )
  }

  private fun alarms(): AlarmManager? = context.getSystemService(AlarmManager::class.java)

  private companion object {
    const val TAG = "ReminderBook"
    const val CHANNEL = "reminders"
    const val CATCH_UP_MILLIS = 3 * 60 * 60 * 1000L
  }
}
