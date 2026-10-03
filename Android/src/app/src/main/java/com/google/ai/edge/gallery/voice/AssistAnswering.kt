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

package com.google.ai.edge.gallery.voice

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.gallery.agent.AssistantExecutor
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val TAG = "AGAssistAnswering"

/**
 * Answers a question from outside the app, when that is possible at all.
 *
 * The pop-up runs in the app's own process, so when the chat screen has a model loaded the engine
 * is right there and a question can be answered without opening anything. When it does not - the
 * app was not running, or its model was released - there is nothing to answer with: loading a model
 * takes tens of seconds and gigabytes of memory, which is not something to start behind a card
 * floating over someone's map. That case opens the chat instead, and says so.
 *
 * Deciding which by trying and catching the failure, rather than by inspecting the model's state
 * first, is deliberate: a model can be released between the check and the call, and the fallback
 * has to be the same either way.
 */
object AssistAnswering {

  /**
   * How long to wait before giving up and opening the chat.
   *
   * Generous, because a small phone model answering a real question is not fast, and a summon that
   * gave up early would look like the assistant ignoring the question. Not unbounded, because the
   * pop-up is over someone else's app and a card that never resolves cannot be reasoned about.
   */
  private const val ANSWER_TIMEOUT_MILLIS = 60_000L

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface AssistantEntryPoint {
    @AssistantExecutor fun executor(): AgentRuntimeExecutor

    fun dataStoreRepository(): DataStoreRepository
  }

  private fun entryPoint(context: Context): AssistantEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, AssistantEntryPoint::class.java)

  /** True when the user wants the pop-up rather than the whole app opening over what they were doing. */
  fun popupEnabled(context: Context): Boolean =
    !entryPoint(context).dataStoreRepository().readAssistantSettings().assistPopupDisabled

  /**
   * Runs one turn against the already-loaded model.
   *
   * Returns null when there is nothing loaded to run against, which the caller turns into opening
   * the chat.
   */
  suspend fun answer(context: Context, query: String, image: Bitmap? = null): String? {
    val executor = entryPoint(context).executor()
    if (executor.activeSessionId == null) {
      Log.d(TAG, "No assistant session is live, so the pop-up cannot answer here")
      return null
    }
    // One at a time. There is a single conversation behind the engine, shared with the chat
    // screen, and starting a second turn into it while the first is still running is not something
    // the runtime is asked to survive. Falling back to opening the chat is the same answer this
    // gives whenever it cannot answer in place.
    if (!inFlight.compareAndSet(false, true)) {
      Log.d(TAG, "A turn is already running, so the pop-up hands this one to the chat")
      return null
    }
    return try {
      val response =
        withTimeout(ANSWER_TIMEOUT_MILLIS) {
          executor.execute(
            context = AgentExecutionContext(),
            request =
              AgentRequest(
                query = query,
                // A model that cannot see simply ignores this; there is no way to ask the engine
                // first, and refusing to attach it would leave vision models unable to answer
                // about the screen at all.
                attachments = image?.let { listOf(Attachment.ImageBitmap(it)) }.orEmpty(),
              ),
          )
        }
      response.output.trim().ifEmpty { null }
    } catch (e: TimeoutCancellationException) {
      Log.w(TAG, "The model did not answer in time; handing over to the chat")
      null
    } catch (e: Exception) {
      // Most often the model was released after the screen went away, which reads as an ordinary
      // error rather than anything the user did.
      Log.w(TAG, "Could not answer in the pop-up", e)
      null
    } finally {
      inFlight.set(false)
    }
  }

  /** Whether a pop-up turn is already running. */
  private val inFlight = AtomicBoolean(false)
}
