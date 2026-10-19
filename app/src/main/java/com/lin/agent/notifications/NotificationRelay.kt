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

package com.lin.agent.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A notification somebody could be replied to through. */
data class Repliable(
  val key: String,
  val app: String,
  val from: String,
  val text: String,
  val postedAt: Long,
)

/** A reply written but not sent. Waiting on the person, not on the model. */
data class StagedReply(val to: String, val app: String, val text: String, val key: String)

/**
 * Notifications that can be replied to, and the one reply waiting to be sent.
 *
 * Held in memory only. Notifications are other people's messages passing through this phone,
 * and writing them to disk so an assistant can search them later is not a thing this app is
 * going to do quietly - what is here lives as long as the notification does.
 *
 * Nothing here sends on its own. [send] exists and is called from the screen, after the person
 * has seen the exact words. That is the whole shape of this feature: a notification reply goes
 * out the instant the action fires, with no composer and no undo, so the model writes and the
 * person sends.
 */
@Singleton
class NotificationRelay @Inject constructor() {

  private val actions = mutableMapOf<String, Notification.Action>()

  private val _repliable = MutableStateFlow<List<Repliable>>(emptyList())
  val repliable: StateFlow<List<Repliable>> = _repliable.asStateFlow()

  private val _staged = MutableStateFlow<StagedReply?>(null)

  /** The reply on screen awaiting a tap, if any. */
  val staged: StateFlow<StagedReply?> = _staged.asStateFlow()

  @Synchronized
  fun remember(notification: Repliable, action: Notification.Action) {
    actions[notification.key] = action
    _repliable.value =
      (_repliable.value.filterNot { it.key == notification.key } + notification)
        .sortedByDescending { it.postedAt }
        .take(KEEP)
  }

  @Synchronized
  fun forget(key: String) {
    actions.remove(key)
    _repliable.value = _repliable.value.filterNot { it.key == key }
    if (_staged.value?.key == key) _staged.value = null
  }

  /**
   * The most recent notification from someone matching [who], or null.
   *
   * Loose matching on purpose: the model is passing on a name the user said out loud, and
   * "Ben" should reach a notification titled "Ben Elbit". Most recent first, because when two
   * conversations with one person are open the newer is the one being talked about.
   */
  fun findFrom(who: String): Repliable? {
    val needle = who.trim().lowercase()
    if (needle.isEmpty()) return null
    return _repliable.value.firstOrNull {
      it.from.lowercase().contains(needle) || needle.contains(it.from.lowercase())
    }
  }

  /** Puts a reply on screen. It is not sent until somebody taps Send. */
  fun stage(reply: StagedReply) {
    _staged.value = reply
  }

  fun discard() {
    _staged.value = null
  }

  /**
   * Actually sends the staged reply.
   *
   * Called from the screen's Send button and from nowhere else - not from a tool, and not from
   * anything the model can reach. A notification reply has no composer and no undo: it is gone
   * the moment this runs.
   */
  fun send(context: Context): Boolean {
    val reply = _staged.value ?: return false
    val action = synchronized(this) { actions[reply.key] } ?: return false
    val input = action.remoteInputs?.firstOrNull() ?: return false

    return runCatching {
        val intent = Intent()
        val bundle = Bundle().apply { putCharSequence(input.resultKey, reply.text) }
        RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)
        action.actionIntent.send(context, 0, intent)
        _staged.value = null
        true
      }
      .onFailure { Log.w(TAG, "Could not send the reply", it) }
      .getOrDefault(false)
  }

  private companion object {
    const val TAG = "LinNotificationRelay"

    /** Recent conversations, not an archive. */
    const val KEEP = 20
  }
}

/**
 * Watches for notifications that can be replied to.
 *
 * Only those: a notification with no reply action is ignored entirely rather than collected,
 * because the point of this service is answering messages and not building a record of
 * everything that crosses somebody's screen.
 *
 * Requires notification access, which only the user can grant in system settings and which the
 * settings screen links to rather than trying to request.
 */
class LinNotificationListener : NotificationListenerService() {

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    val notification = sbn ?: return
    val action = replyAction(notification.notification) ?: return
    val extras = notification.notification.extras

    relay(this)
      ?.remember(
        Repliable(
          key = notification.key,
          app = appLabel(notification.packageName),
          from = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
          text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
          postedAt = notification.postTime,
        ),
        action,
      )
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    sbn?.key?.let { relay(this)?.forget(it) }
  }

  /** The first action carrying free text, which is what a reply action is. */
  private fun replyAction(notification: Notification): Notification.Action? =
    notification.actions?.firstOrNull { action ->
      action.remoteInputs?.any { it.allowFreeFormInput } == true
    }

  private fun appLabel(packageName: String): String =
    runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
      }
      .getOrDefault(packageName)

  private fun relay(context: Context): NotificationRelay? =
    runCatching {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            RelayEntryPoint::class.java,
          )
          .relay()
      }
      .getOrNull()

  @dagger.hilt.EntryPoint
  @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
  interface RelayEntryPoint {
    fun relay(): NotificationRelay
  }
}
