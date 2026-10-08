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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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
  /** Tried in order until one loads. See [DeviceChoice.acceleratorChain]. */
  private val accelerators: List<DeviceChoice.Accelerator> =
    DeviceChoice.acceleratorChain(DeviceChoice.Accelerator.GPU),
  private val supportsImages: Boolean = false,
  private val supportsAudio: Boolean = false,
  /** Only used for an NPU backend, which needs to find its vendor libraries. */
  private val nativeLibraryDir: String? = null,
  private val baseId: String = "on-device",
) : LlmBackend, AutoCloseable {

  /**
   * The name shown to the user, with the accelerator once one has been settled on.
   *
   * Worth showing rather than hiding. CPU is several times slower than GPU on these models, so a
   * reply that takes twenty seconds is the expected behaviour on one and a symptom on the other -
   * and the only way to tell from the outside is to be told which is running.
   */
  override val id: String
    get() = activeAccelerator?.let { "$baseId · $it" } ?: baseId

  /** Which accelerator the loaded engine is actually using. Null until it loads. */
  @Volatile private var activeAccelerator: DeviceChoice.Accelerator? = null

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

    // Each accelerator in turn, keeping the last failure to report if none of them work. The
    // first attempt failing is ordinary - see DeviceChoice.acceleratorChain - so it is logged
    // rather than shown, and only an exhausted chain becomes an error in the conversation.
    var lastFailure: String? = null
    for (attempt in accelerators) {
      val failure = tryLoad(attempt)
      if (failure == null) {
        activeAccelerator = attempt
        return null
      }
      Log.w(TAG, "Could not load $modelPath on $attempt: $failure")
      lastFailure = failure
      closeQuietly()
    }

    val message = lastFailure ?: "The model could not be loaded."
    loadFailure = message
    return message
  }

  /** One load attempt, on one accelerator. Returns null on success, or a message. */
  private fun tryLoad(accelerator: DeviceChoice.Accelerator): String? =
    try {
      val started =
        Engine(
          EngineConfig(
            modelPath = modelPath,
            backend = asLiteRtBackend(accelerator),
            // Vision is a GPU-only path in these builds, so it is available exactly when the
            // engine itself is on the GPU. Asking for a GPU vision encoder beside a CPU engine
            // is how the fallback attempt would fail for the same reason as the attempt before
            // it - which would make the fallback pointless.
            visionBackend =
              if (supportsImages && accelerator == DeviceChoice.Accelerator.GPU) {
                asLiteRtBackend(DeviceChoice.Accelerator.GPU)
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
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
          )
        )
      null
    } catch (e: Throwable) {
      // Throwable, not Exception: a model too large for the device fails with OutOfMemoryError,
      // which is an Error. It went straight past the previous app's handler and killed the
      // process, and a crash during model load is one the user cannot be shown or escape from.
      explain(e)
    }

  /**
   * Streams a reply, loading the model first if it is not already loaded.
   *
   * `flowOn(Dispatchers.IO)` is the important part and it is not a detail. Loading these weights
   * takes seconds to tens of seconds, and this used to run wherever `converse` was called from -
   * which is a view model's scope, which is the main thread. Two and a half gigabytes read on the
   * main thread is a frozen app and then an ANR, and it happened before the first token of the
   * first conversation, where it looks like the app simply not responding to Send.
   */
  override fun converse(request: TurnRequest): Flow<TurnEvent> =
    flow {
        val failure = ensureLoaded()
        if (failure != null) {
          emit(TurnEvent.Failed(message = failure))
          return@flow
        }
        val live = conversation
        if (live == null) {
          emit(TurnEvent.Failed("The model is not loaded."))
          return@flow
        }
        val prompt = request.messages.lastOrNull { it.role == Message.Role.USER }?.text
        if (prompt == null) {
          emit(TurnEvent.Failed("There was nothing to send."))
          return@flow
        }
        emitAll(stream(live, prompt))
      }
      .flowOn(Dispatchers.IO)

  /**
   * The reply itself, once there is a loaded conversation to ask.
   *
   * callbackFlow rather than the engine's own Flow overload, because the callback form is the one
   * the previous app proved against these models, and it gives somewhere to cancel the native
   * work from when the collector goes away.
   */
  private fun stream(live: Conversation, prompt: String): Flow<TurnEvent> {
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

    // Reaching the user means every accelerator failed, CPU included, so the GPU is no longer
    // the actionable part. Note for whoever sees this during development rather than in the
    // wild: an OpenCL failure on a device that plainly has OpenCL means libOpenCL.so is missing
    // from the manifest's uses-native-library list, not that the hardware is incapable.
    text.contains("opencl") ->
      "The model would not start on the graphics processor or the main one. A smaller model is " +
        "the next thing to try."

    else -> error.message ?: "The model failed for a reason it did not explain."
  }
}
