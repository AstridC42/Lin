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

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * A model running on the phone, through LiteRT-LM.
 *
 * The engine and its conversation are created once and held, because that is what being resident
 * means: loading three and a half gigabytes of weights takes long enough that doing it per turn
 * would make the assistant unusable, and the whole design assumes the model is warm.
 *
 * Deliberately narrow. Everything about *what* to say lives above this; this file knows how to
 * start an engine, stream a reply, and fail in a way somebody can act on.
 */
class OnDeviceBackend(
  private val modelPath: String,
  /** Where the engine may write its own cache. Null lets it decide. */
  private val cacheDir: String? = null,
  private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
  private val accelerator: DeviceChoice.Accelerator = DeviceChoice.acceleratorFor(
    DeviceChoice.Accelerator.GPU
  ),
  private val supportsImages: Boolean = false,
  private val supportsAudio: Boolean = false,
  /** Only used for an NPU backend, which needs to find its vendor libraries. */
  private val nativeLibraryDir: String? = null,
  override val id: String = "on-device",
) : LlmBackend, AutoCloseable {

  override val capabilities =
    BackendCapabilities(
      supportsTools = true,
      supportsImages = supportsImages,
      supportsAudio = supportsAudio,
      contextWindow = contextTokens,
    )

  @Volatile private var engine: Engine? = null
  @Volatile private var conversation: Conversation? = null

  /** The failure from the last load attempt, so a turn can report it rather than hang. */
  @Volatile private var loadFailure: String? = null

  /** Reachable means loaded, and loading it is this method's job if it has not happened. */
  override suspend fun reachable(): Boolean = ensureLoaded() == null

  /**
   * Loads the engine if it is not already loaded, returning a message on failure.
   *
   * `Throwable`, not `Exception`. A model too large for the device fails with `OutOfMemoryError`,
   * which is an `Error` - it went straight past the previous app's handler and killed the process,
   * and a crash during model load is one the user cannot be shown or escape from. A failure the
   * app survives lets them pick a smaller model.
   *
   * This cannot catch an allocation that fails inside the engine's native code: that ends the
   * process with no Java frame running. The launch breadcrumb is what covers that case.
   */
  @Synchronized
  private fun ensureLoaded(): String? {
    conversation?.let {
      return null
    }
    loadFailure?.let {
      return it
    }

    if (!File(modelPath).exists()) {
      return "The model file is not on the device yet.".also { loadFailure = it }
    }

    return try {
      val started = Engine(
        EngineConfig(
          modelPath = modelPath,
          backend = asLiteRtBackend(accelerator),
          visionBackend =
            if (supportsImages) {
              DeviceChoice.visionAccelerator()?.let { asLiteRtBackend(it) }
            } else null,
          audioBackend =
            if (supportsAudio) asLiteRtBackend(DeviceChoice.audioAccelerator()) else null,
          maxNumTokens = contextTokens,
          cacheDir = cacheDir,
        )
      )
      started.initialize()
      engine = started
      conversation =
        started.createConversation(
          ConversationConfig(
            // Greedy. Sampling many candidates at temperature while emitting an exact grammar is
            // how a tool call comes out malformed, and it cost the previous app days to find.
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
          )
        )
      null
    } catch (e: Throwable) {
      Log.e(TAG, "Could not load $modelPath", e)
      val message = explain(e)
      loadFailure = message
      closeQuietly()
      message
    }
  }

  override fun converse(request: TurnRequest): Flow<TurnEvent> {
    val failure = ensureLoaded()
    if (failure != null) return flow { emit(TurnEvent.Failed(message = failure)) }
    val live = conversation ?: return flow { emit(TurnEvent.Failed("The model is not loaded.")) }

    val prompt =
      request.messages.lastOrNull { it.role == Message.Role.USER }?.text
        ?: return flow { emit(TurnEvent.Failed("There was nothing to send.")) }

    // callbackFlow rather than the engine's own Flow overload, because the callback form is the
    // one the previous app proved against these models, and it gives somewhere to cancel the
    // native work from when the collector goes away.
    return callbackFlow {
      val accumulated = StringBuilder()
      val callback =
        object : MessageCallback {
          override fun onMessage(message: LiteRtMessage) {
            // Each emission is a delta, not a snapshot - established from the engine's own
            // callers, which append. toString() is how text comes off a Message.
            val delta = message.toString()
            if (delta.isNotEmpty()) {
              accumulated.append(delta)
              trySend(TurnEvent.Token(delta))
            }
          }

          override fun onDone() {
            trySend(TurnEvent.Done(accumulated.toString()))
            channel.close()
          }

          override fun onError(throwable: Throwable) {
            Log.e(TAG, "Inference failed", throwable)
            trySend(
              TurnEvent.Failed(
                message = explain(throwable),
                detail = throwable.message.orEmpty(),
              )
            )
            channel.close()
          }
        }

      try {
        // The Contents overload rather than the Message one: Message's constructor is internal
        // to the library, and Contents is the form that will carry an image or a clip later.
        live.sendMessageAsync(Contents.of(prompt), callback)
      } catch (e: Throwable) {
        trySend(TurnEvent.Failed(message = explain(e), detail = e.message.orEmpty()))
        channel.close()
      }

      awaitClose {
        // The collector stopped - the user navigated away or asked something else. Stopping the
        // native work matters here: it holds the engine, so leaving it running would make the
        // next turn queue behind a reply nobody is waiting for.
        runCatching { live.cancelProcess() }
      }
    }
  }

  override fun close() = closeQuietly()

  private fun closeQuietly() {
    runCatching { conversation?.close() }
    runCatching { engine?.close() }
    conversation = null
    engine = null
  }

  private fun asLiteRtBackend(accelerator: DeviceChoice.Accelerator): Backend =
    when (accelerator) {
      DeviceChoice.Accelerator.CPU -> Backend.CPU()
      DeviceChoice.Accelerator.GPU -> Backend.GPU()
      DeviceChoice.Accelerator.NPU -> Backend.NPU(nativeLibraryDir.orEmpty())
    }

  private companion object {
    const val TAG = "LinOnDeviceBackend"

    /**
     * Context asked for by default.
     *
     * The previous app asked for 4,000 - an allowlist default written for a chat demo - and its
     * fixed overhead came to 4,230 tokens before the user typed anything, so the first message of
     * every conversation failed. This app's orchestrator carries about five tools rather than
     * forty-four, so 8,192 is room rather than a floor.
     */
    const val DEFAULT_CONTEXT_TOKENS = 8192
  }
}

/**
 * The engine's failures, in words that say what to do about them.
 *
 * The engine writes for whoever is debugging the engine, and these arrive in the middle of
 * somebody's conversation. The original text is kept by the caller underneath the sentence,
 * because on a phone with no computer attached it is the only account of the failure there is.
 *
 * Matched on wording rather than on a status code, because the code is the same for most of them.
 * When the engine rewords something the match stops firing and the raw message comes through,
 * which is the behaviour this had before.
 */
internal fun explain(error: Throwable): String {
  val text = (error.message ?: error::class.java.simpleName).lowercase()
  return when {
    error is OutOfMemoryError || text.contains("out of memory") || text.contains("oom") ->
      "This model is too large for this phone's free memory. Try the smaller one."

    text.contains("token ids are too long") || text.contains("maximum number of tokens") ->
      "This conversation no longer fits in the model's context. Start a new one."

    text.contains("failed to parse tool calls") || text.contains("parse fc tool calls") ->
      "The model tried to use a tool and wrote the request wrongly, so nothing ran. Asking again " +
        "often works."

    text.contains("not initialized") -> "The model is still loading."

    else -> error.message ?: "The model failed for a reason it did not explain."
  }
}
