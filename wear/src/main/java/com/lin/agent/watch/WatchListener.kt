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

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.lin.agent.core.wear.WearProtocol

/** What the phone sends back: "working on it", and the answer. Woken by Wear OS. */
class WatchListener : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    when (event.path) {
      WearProtocol.PATH_WORKING -> WatchLink.working(String(event.data))
      WearProtocol.PATH_ANSWER -> WearProtocol.answerFrom(event.data)?.let { WatchLink.answered(this, it) }
    }
  }
}
