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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.lin.agent.core.car.carChatToContinue
import com.lin.agent.core.history.SavedLine
import com.lin.agent.history.ChatHistory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * A reply spoken in the car, arriving as text.
 *
 * Shows it at once - Android Auto keeps a spinner up until the notification changes - and hands
 * the answering to [CarChatWorker], because a model takes longer than a receiver may run.
 */
class CarChatReceiver : BroadcastReceiver() {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun history(): ChatHistory
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != CarChat.ACTION_REPLY) return
    val said =
      RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CarChat.KEY_REPLY)?.toString()?.trim()
    if (said.isNullOrEmpty()) return

    val history = EntryPointAccessors.fromApplication(context, Deps::class.java).history()
    val earlier = carChatToContinue(history.chats.value, System.currentTimeMillis())?.lines.orEmpty()
    CarChat.post(context, earlier + SavedLine(true, said), alert = false)
    CarChatWorker.enqueue(context, said)
  }
}
