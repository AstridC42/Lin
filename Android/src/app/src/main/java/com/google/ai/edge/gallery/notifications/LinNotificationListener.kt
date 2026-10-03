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

package com.google.ai.edge.gallery.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

private const val TAG = "AGNotificationListener"

/** One notification, reduced to what a model can reason about. */
data class NotificationSummary(
  val key: String,
  val packageName: String,
  val appName: String,
  val title: String,
  val text: String,
  val postedAtMillis: Long,
  val isClearable: Boolean,
  val canReply: Boolean,
  val actions: List<String>,
)

/**
 * Access to the notification shade.
 *
 * This is the single largest gap between this app and Google Assistant. "Read my messages", "what
 * did I miss", "reply to Sam" and "clear that" are all ordinary things to ask an assistant and all
 * of them need the shade, which no other API exposes.
 *
 * It is also the most invasive permission on the phone: a granted listener sees every notification
 * from every app, including their contents. It stays off unless the user turns it on in system
 * settings - Android does not allow an app to request it any other way, and that is the right
 * design. Nothing here is read until then.
 *
 * The service instance is held statically because that is the only way to reach it: Android
 * constructs and binds it, so the tools cannot be given a reference any other way.
 */
class LinNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    super.onListenerConnected()
    instance = this
    Log.d(TAG, "Notification access is live")
  }

  override fun onListenerDisconnected() {
    instance = null
    Log.d(TAG, "Notification access went away")
    super.onListenerDisconnected()
  }

  override fun onDestroy() {
    instance = null
    super.onDestroy()
  }

  // Notifications are read on demand rather than accumulated: the shade is already the store, and
  // keeping a copy would mean holding the contents of every notification in memory for no reason.
  override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

  override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

  companion object {
    @Volatile private var instance: LinNotificationListener? = null

    /**
     * Whether the user has granted notification access *and* the service is connected.
     *
     * Both matter. The grant can be present while the service is not yet bound, and acting on that
     * would look like an empty shade rather than "not ready".
     */
    fun isConnected(): Boolean = instance != null

    /** Whether the user has granted notification access in system settings. */
    fun isGranted(context: Context): Boolean {
      val enabled =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
          .orEmpty()
      if (enabled.isEmpty()) return false
      val expected = ComponentName(context, LinNotificationListener::class.java)
      return enabled.split(":").any {
        ComponentName.unflattenFromString(it)?.let { c ->
          c.packageName == expected.packageName && c.className == expected.className
        } == true
      }
    }

    /** The system screen where notification access is granted. */
    fun settingsIntent(): Intent =
      Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The notifications currently in the shade, newest first. */
    fun activeNotifications(context: Context): List<NotificationSummary> {
      val service = instance ?: return listOf()
      val active =
        try {
          service.activeNotifications
        } catch (e: Exception) {
          // Throws when the listener has been disconnected between the check and the call.
          Log.e(TAG, "Could not read active notifications", e)
          return listOf()
        }
      return active
        .orEmpty()
        .mapNotNull { it.toSummary(context) }
        .sortedByDescending { it.postedAtMillis }
    }

    /** Dismisses one notification. Returns false when it is not dismissible or already gone. */
    fun dismiss(key: String): Boolean {
      val service = instance ?: return false
      return try {
        service.cancelNotification(key)
        true
      } catch (e: Exception) {
        Log.e(TAG, "Could not dismiss $key", e)
        false
      }
    }

    /** Opens the app the notification came from, by firing its content intent. */
    fun open(key: String): Boolean {
      val sbn = find(key) ?: return false
      val contentIntent = sbn.notification?.contentIntent ?: return false
      return try {
        contentIntent.send()
        true
      } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "The notification's intent has been cancelled", e)
        false
      }
    }

    /**
     * Sends [message] as a reply to a notification that offers one.
     *
     * A reply action carries a `RemoteInput` describing the field the text goes in; filling that in
     * and firing the action is what the shade's own inline reply does. Without the RemoteInput the
     * action would fire with an empty message, which for a messaging app means sending nothing to
     * someone - so a missing one is a failure, not something to push through.
     */
    fun reply(key: String, message: String): Boolean {
      val sbn = find(key) ?: return false
      val action = sbn.notification?.replyAction() ?: return false
      val remoteInputs = action.remoteInputs ?: return false
      if (remoteInputs.isEmpty()) return false

      return try {
        val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val results = Bundle()
        for (input in remoteInputs) {
          results.putCharSequence(input.resultKey, message)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        // Some apps read the reply from the clip data instead of the results bundle.
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.actionIntent.send(instance, 0, intent)
        true
      } catch (e: Exception) {
        Log.e(TAG, "Could not reply to $key", e)
        false
      }
    }

    /** Fires one of a notification's buttons by its visible label. */
    fun runAction(key: String, label: String): Boolean {
      val sbn = find(key) ?: return false
      val action =
        sbn.notification?.actions?.firstOrNull {
          it.title?.toString().equals(label, ignoreCase = true)
        } ?: return false
      return try {
        action.actionIntent.send()
        true
      } catch (e: Exception) {
        Log.e(TAG, "Could not run \"$label\" on $key", e)
        false
      }
    }

    private fun find(key: String): StatusBarNotification? {
      val service = instance ?: return null
      return try {
        service.activeNotifications?.firstOrNull { it.key == key }
      } catch (e: Exception) {
        Log.e(TAG, "Could not look up $key", e)
        null
      }
    }

    /** The first action that takes free-form text, which is what "reply" means here. */
    private fun Notification.replyAction(): Notification.Action? =
      actions?.firstOrNull { action ->
        action.remoteInputs?.any { it.allowFreeFormInput } == true
      }

    private fun StatusBarNotification.toSummary(context: Context): NotificationSummary? {
      val notification = notification ?: return null
      val extras = notification.extras ?: Bundle()
      val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text =
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
          ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

      // A notification with neither a title nor any text is an ongoing status icon - a sync, a VPN,
      // a foreground service. There is nothing in it to tell the user about.
      if (title.isBlank() && text.isBlank()) return null

      return NotificationSummary(
        key = key,
        packageName = packageName,
        appName = appLabel(context, packageName),
        title = title,
        text = text,
        postedAtMillis = postTime,
        isClearable = isClearable,
        canReply = notification.replyAction() != null,
        actions = notification.actions?.mapNotNull { it.title?.toString() }.orEmpty(),
      )
    }

    private fun appLabel(context: Context, packageName: String): String =
      try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
      } catch (e: Exception) {
        packageName
      }
  }
}
