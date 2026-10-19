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
import com.lin.agent.R
import com.lin.agent.core.car.CAR_CHAT_PREFIX
import com.lin.agent.core.car.CAR_CHAT_PROMPT
import com.lin.agent.core.car.carChatToContinue
import com.lin.agent.core.history.SavedChat
import com.lin.agent.core.history.SavedLine
import com.lin.agent.core.history.chatTitle
import com.lin.agent.core.settings.SettingsRepository
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

/**
 * Answers one reply from the car, with the app closed if need be.
 *
 * Expedited, because somebody is driving and waiting; queued one after another, so two quick
 * replies are answered in the order they were said. The conversation is kept in the history as
 * well, so the drive can be read back on the phone afterwards.
 */
class CarChatWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun backends(): ChatBackends

    fun tools(): Tools

    fun settings(): SettingsRepository

    fun history(): ChatHistory
  }

  override suspend fun doWork(): Result {
    val said = inputData.getString(KEY_TEXT)?.trim().orEmpty()
    if (said.isEmpty()) return Result.success()
    val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
    val history = deps.history()
    history.ready()

    val now = System.currentTimeMillis()
    val earlier = carChatToContinue(history.chats.value, now)
    val id = earlier?.id ?: (CAR_CHAT_PREFIX + UUID.randomUUID())
    val asked = earlier?.lines.orEmpty() + SavedLine(true, said)
    history.save(SavedChat(id, chatTitle(asked), now, asked))

    val answer =
      try {
        answer(deps, asked)
      } catch (stopped: CancellationException) {
        throw stopped
      } catch (failure: Throwable) {
        Log.w(TAG, "The car reply could not be answered", failure)
        null
      } ?: context.getString(R.string.car_chat_failed)

    val lines = asked + SavedLine(false, answer)
    history.save(SavedChat(id, chatTitle(lines), System.currentTimeMillis(), lines))
    CarChat.post(context, lines, alert = true)
    return Result.success()
  }

  private suspend fun answer(deps: Deps, lines: List<SavedLine>): String? =
    when (val reply = HeadlessTurn(deps.backends(), deps.tools(), deps.settings()).answer(lines, CAR_CHAT_PROMPT)) {
      is HeadlessTurn.Reply.Answered -> reply.text
      HeadlessTurn.Reply.NoModel -> context.getString(R.string.car_chat_no_model)
      HeadlessTurn.Reply.Empty -> null
    }

  /** Only used before Android 12, where expedited work runs as a foreground service. */
  override suspend fun getForegroundInfo(): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(
      NotificationChannel(WORKING_CHANNEL, context.getString(R.string.car_chat_channel), NotificationManager.IMPORTANCE_LOW)
    )
    val notification =
      NotificationCompat.Builder(context, WORKING_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.car_chat_thinking))
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(WORKING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(WORKING_ID, notification)
    }
  }

  companion object {
    private const val TAG = "LinCarChat"
    private const val KEY_TEXT = "text"
    private const val WORK = "car-chat"
    private const val WORKING_CHANNEL = "lin-car-chat-working"
    private const val WORKING_ID = 46

    fun enqueue(context: Context, said: String) {
      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          WORK,
          ExistingWorkPolicy.APPEND_OR_REPLACE,
          OneTimeWorkRequestBuilder<CarChatWorker>()
            .setInputData(workDataOf(KEY_TEXT to said))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build(),
        )
    }
  }
}
