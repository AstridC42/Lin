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

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.lin.agent.core.wear.WearProtocol

/**
 * The phone's end of Lin on the watch. Woken by Wear OS when the watch sends a question, even
 * with the app closed; it acknowledges at once, so the watch can say Lin is working on it, and
 * hands the answering to [WatchWorker], because a model takes longer than a service is given.
 */
class WearBridge : WearableListenerService() {

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WearProtocol.PATH_ASK) return
    val question = WearProtocol.questionFrom(event.data) ?: return
    Log.i(TAG, "A question from the watch")
    Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, WearProtocol.PATH_WORKING, question.id.toByteArray())
    WatchWorker.enqueue(this, question, event.sourceNodeId)
  }

  private companion object {
    const val TAG = "LinWearBridge"
  }
}
