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

package com.lin.agent.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A backend with no model behind it.
 *
 * Here from the first commit on purpose. It lets the conversation plumbing, the UI and later the
 * whole agent loop be exercised before any weights exist and without a device - which is the test
 * seam the previous app never had, and the reason its agent behaviour could only ever be checked
 * by hand on a phone.
 *
 * It also makes the skeleton runnable: the first build of this app can hold a conversation, which
 * means the screen, the view model and the streaming are all proven before Phase 1 swaps a real
 * engine in underneath them.
 */
class FakeBackend(
  override val id: String = "fake",
  private val reply: (TurnRequest) -> String = { request ->
    "You said: ${request.messages.lastOrNull { it.role == Message.Role.USER }?.text.orEmpty()}"
  },
  /** Non-zero in the app so streaming looks like streaming; zero in tests so they do not wait. */
  private val perTokenDelayMillis: Long = 0,
  private val failWith: String? = null,
) : LlmBackend {

  override val capabilities =
    BackendCapabilities(
      supportsTools = true,
      supportsImages = false,
      supportsAudio = false,
      contextWindow = 8192,
    )

  override suspend fun reachable(): Boolean = true

  override fun converse(request: TurnRequest): Flow<TurnEvent> = flow {
    failWith?.let {
      emit(TurnEvent.Failed(message = it))
      return@flow
    }
    val text = reply(request)
    // Word at a time, because that is close enough to how a real backend arrives and it exercises
    // the same incremental-render path in the UI.
    val words = text.split(" ")
    for ((index, word) in words.withIndex()) {
      if (perTokenDelayMillis > 0) delay(perTokenDelayMillis)
      emit(TurnEvent.Token(if (index == 0) word else " $word"))
    }
    emit(TurnEvent.Done(text))
  }
}
