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

package com.google.ai.edge.gallery.memory

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val TAG = "LinDigester"

/**
 * Asks the model what a conversation was about.
 *
 * The one thing worth understanding here is the scratch conversation. The obvious way to run a
 * prompt is through the same path the chat uses, and that path sends into the conversation the
 * user is actually having - so digesting would append a summarisation request and its answer to
 * the chat they come back to, and poison the context of their next turn.
 *
 * Instead this opens a second [Conversation] on the engine that is already loaded, uses it once
 * and closes it. The weights are shared, so this costs no extra memory beyond one context; the
 * user's conversation is untouched; and when no engine is loaded there is simply nothing to do
 * and the caller falls back to an extractive digest.
 *
 * Deliberately cold sampling. This is extraction, not conversation: the same transcript should
 * give the same notes, and a creative digester invents biography.
 */
class MemoryDigester {

  /**
   * Summarises [transcript] using [model]'s loaded engine.
   *
   * Returns null when there is no engine to use, when the model could not answer in time, or
   * when nothing usable came back - each of which the caller handles the same way, by writing an
   * extractive digest and trying again later.
   */
  suspend fun digest(model: Model, transcript: String): ParsedDigest? {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      Log.d(TAG, "No engine loaded; nothing to digest with")
      return null
    }

    var scratch: Conversation? = null
    return try {
      scratch =
        instance.engine.createConversation(
          ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            systemInstruction =
              Contents.of(
                "You extract durable facts and write short summaries. You answer only in the " +
                  "format you are asked for."
              ),
          )
        )
      val prompt = DigestParser.prompt(ConversationArchive.forDigesting(transcript))
      val reply = withTimeout(DIGEST_TIMEOUT_MILLIS) { generate(scratch, prompt) }
      DigestParser.parse(reply)
    } catch (e: TimeoutCancellationException) {
      Log.w(TAG, "Digesting timed out")
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Digesting failed", e)
      null
    } finally {
      try {
        scratch?.close()
      } catch (e: Exception) {
        Log.w(TAG, "Could not close the scratch conversation", e)
      }
    }
  }

  /** The whole reply, once the engine says it is finished. */
  private suspend fun generate(conversation: Conversation, prompt: String): String =
    suspendCancellableCoroutine { continuation ->
      val collected = StringBuilder()
      conversation.sendMessageAsync(
        Contents.of(listOf(Content.Text(prompt))),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            collected.append(message.toString())
          }

          override fun onDone() {
            if (continuation.isActive) continuation.resume(collected.toString())
          }

          override fun onError(throwable: Throwable) {
            // Whatever arrived before the failure is still worth parsing: a reply cut off after
            // the summary but before the notes is most of a digest.
            if (continuation.isActive) continuation.resume(collected.toString())
          }
        },
      )
    }

  private companion object {
    /**
     * How long one digest may take.
     *
     * Generous: this is a background job on a phone that may be throttled, and the alternative
     * to waiting is a placeholder summary. Still bounded, because a worker that never returns
     * holds a wake lock.
     */
    const val DIGEST_TIMEOUT_MILLIS = 180_000L
  }
}
