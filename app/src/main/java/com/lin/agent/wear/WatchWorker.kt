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

package com.lin.agent.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.wearable.Wearable
import com.lin.agent.R
import com.lin.agent.core.car.chatToContinue
import com.lin.agent.core.car.forTheCar
import com.lin.agent.core.history.SavedChat
import com.lin.agent.core.history.SavedLine
import com.lin.agent.core.history.chatTitle
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.wear.WearProtocol
import com.lin.agent.history.ChatHistory
import com.lin.agent.model.ChatBackends
import com.lin.agent.model.HeadlessTurn
import com.lin.agent.model.Tools
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Answers one question from the watch, with the app closed if need be, and sends the answer
 * back. Expedited, because somebody is looking at their wrist; queued in order. Questions from
 * the watch are kept in the history like any other conversation.
 */
class WatchWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun backends(): ChatBackends

    fun tools(): Tools

    fun settings(): SettingsRepository

    fun history(): ChatHistory
  }

  override suspend fun doWork(): Result {
    val id = inputData.getString(KEY_ID).orEmpty()
    val asked = inputData.getString(KEY_TEXT)?.trim().orEmpty()
    val node = inputData.getString(KEY_NODE).orEmpty()
    if (asked.isEmpty() || node.isEmpty()) return Result.success()
    val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
    val history = deps.history()
    history.ready()

    val now = System.currentTimeMillis()
    val earlier = chatToContinue(history.chats.value, WearProtocol.CHAT_PREFIX, now)
    val chatId = earlier?.id ?: (WearProtocol.CHAT_PREFIX + UUID.randomUUID())
    val lines = earlier?.lines.orEmpty() + SavedLine(true, asked)
    history.save(SavedChat(chatId, chatTitle(lines), now, lines))

    val reply =
      try {
        HeadlessTurn(deps.backends(), deps.tools(), deps.settings()).answer(lines, WearProtocol.PROMPT)
      } catch (stopped: CancellationException) {
        throw stopped
      } catch (failure: Throwable) {
        Log.w(TAG, "The watch's question could not be answered", failure)
        HeadlessTurn.Reply.Empty
      }
    val answer =
      when (reply) {
        is HeadlessTurn.Reply.Answered -> WearProtocol.Answer(id, forTheCar(reply.text), ok = true)
        HeadlessTurn.Reply.NoModel -> WearProtocol.Answer(id, context.getString(R.string.watch_no_model), ok = false)
        HeadlessTurn.Reply.Empty -> WearProtocol.Answer(id, context.getString(R.string.watch_failed), ok = false)
      }
    if (reply is HeadlessTurn.Reply.Answered) {
      val full = lines + SavedLine(false, reply.text)
      history.save(SavedChat(chatId, chatTitle(full), System.currentTimeMillis(), full))
    }
    runCatching { Wearable.getMessageClient(context).sendMessage(node, WearProtocol.PATH_ANSWER, WearProtocol.answer(answer)).await() }
      .onFailure { Log.w(TAG, "The answer could not reach the watch", it) }
    return Result.success()
  }

  /** Only used before Android 12, where expedited work runs as a foreground service. */
  override suspend fun getForegroundInfo(): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(
      NotificationChannel(CHANNEL, context.getString(R.string.watch_channel), NotificationManager.IMPORTANCE_LOW)
    )
    val notification =
      NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.watch_thinking))
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIFICATION_ID, notification)
    }
  }

  companion object {
    private const val TAG = "LinWatch"
    private const val KEY_ID = "id"
    private const val KEY_TEXT = "text"
    private const val KEY_NODE = "node"
    private const val WORK = "watch-question"
    private const val CHANNEL = "lin-watch-working"
    private const val NOTIFICATION_ID = 47

    fun enqueue(context: Context, question: WearProtocol.Question, node: String) {
      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          WORK,
          ExistingWorkPolicy.APPEND_OR_REPLACE,
          OneTimeWorkRequestBuilder<WatchWorker>()
            .setInputData(workDataOf(KEY_ID to question.id, KEY_TEXT to question.text, KEY_NODE to node))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build(),
        )
    }
  }
}
