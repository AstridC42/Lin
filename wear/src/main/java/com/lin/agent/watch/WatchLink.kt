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

package com.lin.agent.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.lin.agent.core.wear.WearProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Where a question is. */
enum class Status {
  IDLE,
  SENDING,
  /** The phone has it and Lin is answering. */
  WORKING,
  DONE,
  /** No phone with Lin on it is connected. */
  NO_PHONE,
  /** The phone did not answer in time. */
  NO_ANSWER,
}

data class WatchState(
  val question: String = "",
  val answer: String = "",
  val status: Status = Status.IDLE,
  /** Asked by voice, so the answer is read out. */
  val spoken: Boolean = false,
)

/**
 * The watch's side of the conversation: sends the question to the phone and holds the answer
 * when it comes back. One object for the app, because the answer can arrive while the screen is
 * closed and has to be there when it opens.
 */
object WatchLink {

  private val _state = MutableStateFlow(WatchState())
  val state: StateFlow<WatchState> = _state.asStateFlow()

  /** Whether the app is on screen; when it is not, an answer becomes a notification. */
  @Volatile var visible = false

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var pending: String = ""
  private var timeout: Job? = null

  fun ask(context: Context, text: String, spoken: Boolean) {
    val question = text.trim()
    if (question.isEmpty()) return
    val id = UUID.randomUUID().toString()
    pending = id
    _state.value = WatchState(question = question, status = Status.SENDING, spoken = spoken)
    scope.launch {
      val node =
        runCatching {
            Wearable.getCapabilityClient(context)
              .getCapability(WearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
              .await()
              .nodes
              .let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }
          }
          .onFailure { Log.w(TAG, "Could not look for the phone", it) }
          .getOrNull()
      if (node == null) {
        _state.update { it.copy(status = Status.NO_PHONE) }
        return@launch
      }
      val sent =
        runCatching {
            Wearable.getMessageClient(context)
              .sendMessage(node.id, WearProtocol.PATH_ASK, WearProtocol.question(WearProtocol.Question(id, question)))
              .await()
          }
          .isSuccess
      if (!sent) {
        _state.update { it.copy(status = Status.NO_PHONE) }
        return@launch
      }
      timeout?.cancel()
      timeout =
        scope.launch {
          delay(ANSWER_WAIT_MS)
          if (pending == id && _state.value.status != Status.DONE) _state.update { it.copy(status = Status.NO_ANSWER) }
        }
    }
  }

  /** From [WatchListener]: the phone has the question. */
  fun working(id: String) {
    if (id == pending && _state.value.status == Status.SENDING) _state.update { it.copy(status = Status.WORKING) }
  }

  /** From [WatchListener]: the answer. */
  fun answered(context: Context, answer: WearProtocol.Answer) {
    if (answer.id != pending) return
    timeout?.cancel()
    _state.update { it.copy(answer = answer.text, status = Status.DONE) }
    if (!visible) notify(context, _state.value.question, answer.text)
  }

  fun clear() {
    pending = ""
    timeout?.cancel()
    _state.value = WatchState()
  }

  private fun notify(context: Context, question: String, answer: String) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_answers), NotificationManager.IMPORTANCE_HIGH))
    val open =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    manager.notify(
      NOTIFICATION_ID,
      NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(question)
        .setContentText(answer)
        .setStyle(NotificationCompat.BigTextStyle().bigText(answer))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build(),
    )
  }

  private const val TAG = "LinWatchLink"
  private const val CHANNEL = "answers"
  private const val NOTIFICATION_ID = 1
  /** Long enough for a server model and a web search; short enough not to leave a spinner forever. */
  private const val ANSWER_WAIT_MS = 120_000L
}
