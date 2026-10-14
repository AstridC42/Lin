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
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

  /**
   * A second conversation, for everything the plain one cannot express.
   *
   * Two rather than one, and the separation is the safety property. The no-tools, no-prompt path
   * stays exactly the code that is already answering on the device, and a mistake in any of this
   * cannot cost plain conversation.
   *
   * Keyed by the shape it was built for, because a system instruction, a tool set and a response
   * format are all fixed when a conversation is created rather than per message - so a different
   * shape needs a different conversation. Cheap: the weights belong to the engine and are not
   * reloaded.
   *
   * This used to be keyed on tools alone, and everything else a request carried was dropped
   * silently - see [shapeOf].
   */
  @Volatile private var shaped: Conversation? = null

  @Volatile private var shapedFor: TurnShape? = null

  /** The failure from the last load attempt, so a turn can report it rather than hang. */
  @Volatile private var loadFailure: String? = null

  /** Held for the length of a turn, so two callers cannot generate on one engine at once. */
  private val generation = Mutex()

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
            // Greedy, and greedy comes from topK - not from the temperature.
            //
            // topK = 1 keeps exactly one candidate, so whatever scaling happens the survivor is
            // the argmax: deterministic, which is what emitting an exact grammar needs. Sampling
            // many candidates at temperature is how a tool call comes out malformed, and it cost
            // the previous app days to find.
            //
            // The temperature is 1.0 rather than 0.0 and that is not cosmetic. The GPU sampler
            // runs a temperature-annealing step that rejects zero outright - the library carries
            // the string "Annealing temperature cannot be zero" - and the turn comes back as
            // "Status Code: 13 ... Task failed with state: 7", which names neither the sampler
            // nor the temperature. The CPU path tolerated it, so this only appeared once the GPU
            // started working. With one candidate the value cannot change the output anyway.
            samplerConfig =
              SamplerConfig(topK = GREEDY_TOP_K, topP = 1.0, temperature = SAFE_TEMPERATURE)
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
        val prompt = promptFrom(request)
        if (prompt == null) {
          emit(TurnEvent.Failed("There was nothing to send."))
          return@flow
        }
        val shape = shapeOf(request)
        val built = if (shape == null) null else conversationFor(shape)
        // One generation at a time, for as long as this one is being collected.
        //
        // Until the digest there was only ever one caller - a screen somebody was looking at -
        // so turns could not overlap. A worker changes that: it can start while the app is
        // backgrounded and still be running when the user reopens it and asks something. These
        // are two conversations but one engine, with one set of weights behind them, and
        // whatever that does under simultaneous generation is not something that can be checked
        // off a device.
        //
        // So the second one waits. A digest is a few short facts against a schema, which is
        // seconds; waiting seconds is a far better worst case for the user's turn than
        // whichever error two generations at once produces.
        generation.withLock {
          emitAll(
            stream(
              live = built?.conversation ?: live,
              prompt = prompt,
              format = built?.format,
            pictures = if (supportsImages) request.picturesOfLastMessage() else emptyList(),
            thinking = thinkingFor(request.reasoning, structured = request.responseSchema != null),
              // A structured turn's conversation is closed when the turn ends - see [Built].
              closeAfter = built?.takeIf { it.throwaway }?.conversation,
            )
          )
        }
      }
      .flowOn(Dispatchers.IO)

  /**
   * What to send this turn.
   *
   * A tool result is the message the model is waiting for, so it has to be what goes next -
   * taking the last *user* message instead would re-ask the original question and lose the
   * answer the tool just produced, which is an infinite loop with extra steps.
   */
  private fun promptFrom(request: TurnRequest): String? {
    val last = request.messages.lastOrNull() ?: return null
    return when (last.role) {
      Message.Role.TOOL -> "Result of ${last.toolName}:\n${last.text}"
      Message.Role.USER -> last.text
      // Neither, which means the loop is asking the model to continue from its own turn.
      else -> request.messages.lastOrNull { it.role == Message.Role.USER }?.text
    }
  }

  /**
   * A conversation, the format it was built to accept, and whether it is this turn's alone.
   *
   * The format travels with the conversation rather than being read off the request again at
   * send time. A response format is only honoured by a conversation created to allow one, so
   * sending it to any other is at best ignored - and if building the structured conversation
   * failed and the turn fell back to the plain one, reading the request would do exactly that.
   */
  private data class Built(
    val conversation: Conversation,
    val format: ResponseFormat?,
    val throwaway: Boolean,
  )

  /**
   * A conversation built for [shape], or null to fall back to the plain one.
   *
   * Returns null when building fails: the request is then answered by the plain conversation
   * rather than not at all. Degrading to a plain reply is the right failure for something that
   * cannot be verified off a device.
   *
   * A structured turn gets a fresh conversation every time and never shares one. The only
   * structured caller is the digest, which sends a whole transcript - so reusing its
   * conversation would both carry one evening's messages into the next digest and leave them
   * sitting in the engine's cache in between.
   */
  @Synchronized
  private fun conversationFor(shape: TurnShape): Built? {
    if (!shape.structured) {
      shaped?.takeIf { shapedFor == shape }?.let {
        return Built(it, format = null, throwaway = false)
      }
    }

    val started = engine ?: return null
    return try {
      val config =
        ConversationConfig(
          systemInstruction =
            shape.systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
          samplerConfig =
            SamplerConfig(topK = GREEDY_TOP_K, topP = 1.0, temperature = SAFE_TEMPERATURE),
          tools = shape.tools.map { tool(DeclaredTool(it)) },
          // The loop runs the tools, not the engine. It is what scopes them by role, counts
          // the budget and shows the user that something is happening - none of which the
          // engine's own tool loop can do from in there.
          automaticToolCalling = false,
          // Without this the per-message format is ignored and the schema means nothing.
          enableResponseFormat = shape.structured,
        )
      shape.responseSchema?.let { schema ->
        return Built(
          conversation = started.createConversation(config),
          format = ResponseFormat.json(schema),
          throwaway = true,
        )
      }

      runCatching { shaped?.close() }
      val fresh = started.createConversation(config)
      shaped = fresh
      shapedFor = shape
      Built(fresh, format = null, throwaway = false)
    } catch (e: Throwable) {
      Log.w(TAG, "Could not build a conversation for $shape; answering plainly", e)
      if (!shape.structured) {
        shaped = null
        shapedFor = null
      }
      null
    }
  }

  /**
   * The reply itself, once there is a loaded conversation to ask.
   *
   * callbackFlow rather than the engine's own Flow overload, because the callback form is the one
   * the previous app proved against these models, and it gives somewhere to cancel the native
   * work from when the collector goes away.
   */
  private fun stream(
    live: Conversation,
    prompt: String,
    format: ResponseFormat?,
    pictures: List<Attachment.Picture>,
    thinking: ThinkingBudget?,
    closeAfter: Conversation?,
  ): Flow<TurnEvent> {
    return callbackFlow {
      val accumulated = StringBuilder()
      val requested = mutableSetOf<String>()
      val callback =
        object : MessageCallback {
          override fun onMessage(message: LiteRtMessage) {
            // Thinking comes back on the message's channels rather than in its text, so it is
            // read off first and reported as its own event. Every channel, not a channel named
            // "thinking": the name is the model's chat template's business, and guessing it
            // wrong would silently show nothing. Nothing else uses channels today, so anything
            // arriving on one is working-out.
            val thought =
              runCatching { message.channels }
                .getOrNull()
                .orEmpty()
                .values
                .joinToString("")
                .takeIf { it.isNotEmpty() }
            if (thought != null) trySend(TurnEvent.Thought(thought))

            // Each emission is a delta, not a snapshot - established from the engine's own
            // callers, which append. toString() is how text comes off a Message.
            val delta = message.toString()
            if (delta.isNotEmpty()) {
              accumulated.append(delta)
              trySend(TurnEvent.Token(delta))
            }
            // Read off whichever emission carries them rather than assuming it is the last.
            // Deduplicated because a delta may repeat what an earlier one already announced,
            // and running a tool twice for one request is worse than missing a duplicate.
            val calls = runCatching { message.toolCalls }.getOrNull().orEmpty()
            val fresh =
              calls
                .map { ToolInvocation(it.name, it.arguments) }
                .filter { requested.add("${'$'}{it.name}(${'$'}{it.arguments})") }
            if (fresh.isNotEmpty()) trySend(TurnEvent.ToolCalls(fresh))
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
        // to the library, and Contents is what carries a picture beside the words.
        //
        // Pictures first. These models are trained with the image ahead of the question, and
        // asking about something the model has not been shown yet is how a vision turn comes
        // back describing nothing.
        // Branched rather than passing nulls, so a plain turn makes exactly the call that is
        // already proven against these models. Only what a turn actually asked for is passed.
        val thinkingConfig =
          thinking?.let { ThinkingConfig(enableThinking = true, thinkingTokenBudget = it.tokens) }
        val contents =
          if (pictures.isEmpty()) Contents.of(prompt)
          else
            Contents.of(
              pictures.map { Content.ImageBytes(it.bytes) } + Content.Text(prompt)
            )
        when {
          format == null && thinkingConfig == null ->
            live.sendMessageAsync(contents, callback)
          format == null ->
            live.sendMessageAsync(contents, callback, thinkingConfig = thinkingConfig)
          thinkingConfig == null ->
            live.sendMessageAsync(contents, callback, responseFormat = format)
          else ->
            live.sendMessageAsync(
              contents,
              callback,
              thinkingConfig = thinkingConfig,
              responseFormat = format,
            )
        }
      } catch (e: Throwable) {
        trySend(TurnEvent.Failed(message = explain(e), detail = e.message.orEmpty()))
        channel.close()
      }

      awaitClose {
        // The collector stopped - the user navigated away or asked something else. Stopping the
        // native work matters here: it holds the engine, so leaving it running would make the
        // next turn queue behind a reply nobody is waiting for.
        runCatching { live.cancelProcess() }
        // A conversation built for this turn alone goes with it, taking what was sent to it out
        // of the engine's cache rather than leaving it there until the next one.
        closeAfter?.let { runCatching { it.close() } }
      }
    }
  }

  override fun close() = closeQuietly()

  private fun closeQuietly() {
    runCatching { shaped?.close() }
    runCatching { conversation?.close() }
    runCatching { engine?.close() }
    shaped = null
    shapedFor = null
    conversation = null
    engine = null
  }

  private fun asLiteRtBackend(accelerator: DeviceChoice.Accelerator): Backend =
    when (accelerator) {
      DeviceChoice.Accelerator.CPU -> Backend.CPU()
      DeviceChoice.Accelerator.GPU -> Backend.GPU()
      DeviceChoice.Accelerator.NPU -> Backend.NPU(nativeLibraryDir.orEmpty())
    }

  internal companion object {
    private const val TAG = "LinOnDeviceBackend"

    /**
     * Context asked for by default.
     *
     * The previous app asked for 4,000 - an allowlist default written for a chat demo - and its
     * fixed overhead came to 4,230 tokens before the user typed anything, so the first message of
     * every conversation failed. This app's orchestrator carries about five tools rather than
     * forty-four, so 8,192 is room rather than a floor.
     */
    const val DEFAULT_CONTEXT_TOKENS = 8192

    /** One candidate, so decoding is the argmax and nothing is sampled. */
    const val GREEDY_TOP_K = 1

    /**
     * Any non-zero value. See the note where the sampler is built: zero is rejected by the GPU
     * sampler's annealing step, and with [GREEDY_TOP_K] candidates it cannot affect the output.
     */
    const val SAFE_TEMPERATURE = 1.0
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

    // "Prefill input length exceeds available state entries (remaining capacity: 2906)" - the
    // same thing in the engine's other vocabulary, and the one a long attachment produces.
    // Worth its own branch because the words share nothing with the case above, and because
    // the fix is specific: the engine holds this conversation's history itself, so trimming
    // what this app sends cannot shrink it. Only a new conversation can.
    text.contains("prefill input length") || text.contains("available state entries") ->
      "That was too long for what is left of the model's memory on this conversation. Start a " +
        "new one with the + button and attach it again - or let the server answer, which has " +
        "far more room."

    // Reaching the user means the retry in TurnLoop failed too - it is told to try again by
    // isMalformedToolCall, which matches this sentence as well as the parser's own words. So
    // this no longer suggests asking again: it has already been asked again.
    text.contains("failed to parse tool calls") || text.contains("parse fc tool calls") ->
      "The model wrote a tool call that could not be read, twice over, so nothing ran. Asking " +
        "for the same thing in different words usually gets past it."

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

/**
 * A tool as LiteRT-LM wants to be told about it.
 *
 * [execute] is never called. The loop runs tools itself - that is what scopes them by role and
 * counts the budget - so the conversation is created with `automaticToolCalling = false` and the
 * engine only ever reads the declaration. Returning an empty object rather than throwing keeps
 * that true even if some path does reach it: an empty result is a wasted turn, an exception
 * thrown from inside the engine is a lost conversation.
 */
private class DeclaredTool(private val spec: ToolSpec) : OpenApiTool {

  override fun getToolDescriptionJsonString(): String =
    org.json.JSONObject()
      .put("name", spec.name)
      .put("description", spec.description)
      .put("parameters", org.json.JSONObject(spec.parametersSchema))
      .toString()

  override fun execute(input: String): String = "{}"
}
