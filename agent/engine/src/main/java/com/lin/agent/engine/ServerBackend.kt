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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/**
 * A model running on the user's own Ollama server.
 *
 * The same interface as the on-device engine, which is the point of having an interface: above
 * this line, "answer on the phone" and "answer on the server" are the same code path with a
 * different object in it.
 *
 * Nothing is held open between turns. There is no engine to keep warm here - the weights live on
 * the server and it decides what to keep resident - so a backend is cheap to build, cheap to
 * throw away, and safe to rebuild whenever the address or the chosen model changes.
 */
class ServerBackend(
  private val baseUrl: String,
  private val authHeader: String,
  private val modelName: String,
  /**
   * Which dialect to speak.
   *
   * Defaults to Ollama, which is what this app shipped with. One backend rather than two
   * because the loop below is identical for all of them - what differs is the address, the
   * request body and the shape of a streamed chunk, and each of those is one call.
   */
  private val kind: ServerKind = ServerKind.OLLAMA,
  /**
   * Whether to trust the model name without finding it in the server's list.
   *
   * Set when the user typed the name rather than picking it, which is the only case where
   * there is something to trust. See [reachable].
   */
  private val assumeModelExists: Boolean = false,
  /**
   * How this server should generate, as the user set it on the settings screen.
   *
   * A property of the server rather than of a turn, which is why it is here beside the model
   * name rather than on [TurnRequest]. Empty by default, and empty means the server's own
   * configuration is left alone.
   */
  private val sampling: Sampling = Sampling(),
  private val server: ModelServer = ModelServer(),
  private val openStream: (url: String, headers: Map<String, String>, body: String) -> Stream =
    ::httpStream,
) : LlmBackend {

  /** A streamed response: one JSON object per line from Ollama, or SSE events from the rest. */
  interface Stream : AutoCloseable {
    val status: Int

    /** Consumed lazily, so tokens reach the screen as they arrive rather than at the end. */
    val lines: Sequence<String>

    /** The body of a non-200, which is where a proxy or the server puts its reason. */
    val errorBody: String
  }

  override val id: String = "$modelName · server"

  override val capabilities =
    BackendCapabilities(
      // Tools are offered because the protocol supports them and a model that does not
      // understand them simply never asks for one - which the loop handles as an ordinary reply.
      // Images stay false: that would fail at the point of use, where it is hardest to explain,
      // and nothing here knows which model was picked.
      supportsTools = true,
      supportsImages = false,
      supportsAudio = false,
      // Zero means "the server's own setting", which is the default: it is configured per
      // model on the server, and inventing a number here would only be wrong. When the user
      // has set one, that is the number Lin also trims the conversation to - otherwise the
      // phone would keep sending more than the window it just asked the server for.
      contextWindow = sampling.sane().contextTokens ?: UNKNOWN_CONTEXT,
    )

  /**
   * Whether the server is there *and* still has this model.
   *
   * Both halves matter. A server that is reachable but no longer has the chosen model would fail
   * every turn with a 404 that reads like the server being broken, when what actually happened is
   * that somebody removed a model.
   */
  override suspend fun reachable(): Boolean =
    when (val result = server.models(baseUrl, authHeader, kind)) {
      is ServerModelsResult.Found -> result.models.any { it.name == modelName }
      // A server that will not list its models is not necessarily a server that is down.
      // Unsloth's own documentation tells people to type a model id in by hand when their
      // server does not expose /models, so treating the list as proof of life would make this
      // app fall back to the phone for ever against a server that answers every chat request
      // perfectly well. When the name was typed rather than picked, the user has asserted the
      // model exists and the turn itself is what finds out.
      is ServerModelsResult.Failed -> assumeModelExists
    }

  override fun converse(request: TurnRequest): Flow<TurnEvent> =
    flow {
        val url = serverUrl(baseUrl, kind, Endpoint.CHAT)
        if (url == null) {
          emit(TurnEvent.Failed(ModelServer.NO_ADDRESS))
          return@flow
        }

        val answer = StringBuilder()
        var understoodAnything = false
        try {
          val body =
            if (kind.speaksOpenAi) openAiRequestBody(modelName, request, sampling)
            else chatRequestBody(modelName, request, sampling)
          openStream(url, headersFrom(authHeader), body).use { s ->
            if (s.status != HTTP_OK) {
              emit(
                TurnEvent.Failed(describeStatus(s.status, kind), s.errorBody.take(DETAIL_CHARS))
              )
              return@use
            }
            for (line in s.lines) {
              val chunk =
                (if (kind.speaksOpenAi) readOpenAiChunk(line) else readChunk(line)) ?: continue
              understoodAnything = true
              if (chunk.error.isNotEmpty()) {
                emit(TurnEvent.Failed(serverSaid(chunk.error), chunk.error))
                return@use
              }
              // Before the answer, because that is the order it was produced in.
              if (chunk.thinking.isNotEmpty()) emit(TurnEvent.Thought(chunk.thinking))
              if (chunk.text.isNotEmpty()) {
                answer.append(chunk.text)
                emit(TurnEvent.Token(chunk.text))
              }
              if (chunk.toolCalls.isNotEmpty()) emit(TurnEvent.ToolCalls(chunk.toolCalls))
              if (chunk.done) {
                emit(TurnEvent.Done(answer.toString()))
                return@use
              }
            }
            // The stream ended without the done flag. Keeping whatever arrived is better than
            // discarding a reply that was merely cut short.
            when {
              answer.isNotEmpty() -> emit(TurnEvent.Done(answer.toString()))
              !understoodAnything -> emit(TurnEvent.Failed(ModelServer.wrongDialect(kind)))
              else ->
                emit(TurnEvent.Failed("The server accepted the request and sent nothing back."))
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          Log.w(TAG, "Turn against $url failed", e)
          emit(TurnEvent.Failed(describeFailure(e), e.message.orEmpty()))
        }
      }
      .flowOn(Dispatchers.IO)

  private fun serverSaid(error: String): String =
    if (error.contains("not found", ignoreCase = true)) {
      "The server does not have \"$modelName\" any more. Pick another in settings."
    } else {
      "The server could not answer."
    }

  companion object {
    private const val TAG = "LinServerBackend"
    private const val HTTP_OK = 200
    private const val DETAIL_CHARS = 300

    /** The server decides, and this app does not ask. */
    const val UNKNOWN_CONTEXT = 0

    private const val CONNECT_TIMEOUT_MS = 10_000

    /**
     * Generous, and between bytes rather than in total.
     *
     * A server asked for a model it does not have resident loads it into VRAM first, and for a
     * large model that is tens of seconds before the first token. Once tokens are flowing the
     * gaps are tiny, so this only ever bites on a cold start - where a short timeout would look
     * exactly like the server being broken.
     */
    private const val READ_TIMEOUT_MS = 180_000

    private fun httpStream(
      url: String,
      headers: Map<String, String>,
      body: String,
    ): Stream {
      val connection =
        (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          doOutput = true
          connectTimeout = CONNECT_TIMEOUT_MS
          readTimeout = READ_TIMEOUT_MS
          setRequestProperty("Content-Type", "application/json")
          headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      val ok = code in 200..299
      return object : Stream {
        override val status: Int = code

        override val lines: Sequence<String> =
          if (ok) connection.inputStream.bufferedReader().lineSequence() else emptySequence()

        override val errorBody: String =
          if (ok) "" else connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()

        override fun close() = connection.disconnect()
      }
    }
  }
}

/** One line of Ollama's streamed answer, reduced to what a turn does with it. */
internal data class ChatChunk(
  val text: String = "",
  val done: Boolean = false,
  val error: String = "",
  val toolCalls: List<ToolInvocation> = emptyList(),
  /** The model's working-out, which Ollama reports separately from its answer. */
  val thinking: String = "",
)

/**
 * Reads one streamed line, or null if there is nothing in it worth acting on.
 *
 * Text and the done flag are read from the same line rather than treated as alternatives: a final
 * chunk is allowed to carry content as well as `done`, and handling them as either/or would drop
 * the last word of every reply on a server that does that.
 */
internal fun readChunk(line: String): ChatChunk? {
  val trimmed = line.trim()
  if (trimmed.isEmpty()) return null
  val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

  val error = json.optString("error")
  if (error.isNotEmpty()) return ChatChunk(error = error)

  val message = json.optJSONObject("message")
  return ChatChunk(
    text = message?.optString("content").orEmpty(),
    done = json.optBoolean("done", false),
    toolCalls = message?.let(::readToolCalls).orEmpty(),
    // Read off `thinking` rather than left in `content`, which is where Ollama puts it, so it
    // cannot end up read as part of the answer.
    thinking = message?.optString("thinking").orEmpty(),
  )
}

/**
 * The tool calls on one message, in Ollama's shape: `tool_calls[].function.{name,arguments}`.
 *
 * Anything without a name is dropped rather than passed on. A call the loop cannot look up would
 * only come back as "there is no tool called null", which wastes a round trip to say nothing.
 */
internal fun readToolCalls(message: JSONObject): List<ToolInvocation> {
  val calls = message.optJSONArray("tool_calls") ?: return emptyList()
  return (0 until calls.length()).mapNotNull { index ->
    val function = calls.optJSONObject(index)?.optJSONObject("function") ?: return@mapNotNull null
    val name = function.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val arguments = function.optJSONObject("arguments")
    ToolInvocation(
      name = name,
      arguments = arguments?.keys()?.asSequence()?.associateWith { arguments.get(it) }.orEmpty(),
    )
  }
}

/**
 * The request body for `POST /api/chat`.
 *
 * Built with JSONObject rather than string templates, because a conversation contains quotes and
 * newlines and an escaping mistake here would corrupt whatever the user actually typed.
 *
 * `num_ctx` is sent only when the user has set one. The context window is the server's own
 * configuration per model and this app has no business overriding it by default - but the
 * person who owns the server asked to be able to, and a setting they deliberately changed is
 * not this app second-guessing them.
 */
internal fun chatRequestBody(
  model: String,
  request: TurnRequest,
  sampling: Sampling = Sampling(),
): String {
  val messages = JSONArray()
  if (request.systemPrompt.isNotBlank()) {
    messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
  }
  for (message in request.messages) {
    messages.put(
      JSONObject().put("role", wireRole(message.role)).put("content", message.text).apply {
        if (message.toolName.isNotEmpty()) put("tool_name", message.toolName)
        // Ollama takes pictures as bare base64 alongside the text, not as content parts.
        val pictures = message.attachments.filterIsInstance<Attachment.Picture>()
        if (pictures.isNotEmpty()) {
          val images = JSONArray()
          pictures.forEach { images.put(base64(it.bytes)) }
          put("images", images)
        }
      }
    )
  }

  val body =
    JSONObject()
      .put("model", model)
      .put("messages", messages)
      .put("stream", true)
      .put("options", ollamaOptions(samplingFor(sampling, request, request.responseSchema != null)))

  if (request.tools.isNotEmpty()) {
    val tools = JSONArray()
    for (tool in request.tools) {
      tools.put(
        JSONObject()
          .put("type", "function")
          .put(
            "function",
            JSONObject()
              .put("name", tool.name)
              .put("description", tool.description)
              .put("parameters", JSONObject(tool.parametersSchema)),
          )
      )
    }
    body.put("tools", tools)
  }

  // Ollama takes the schema itself as `format`, which is its constrained-decoding switch.
  request.responseSchema?.let { body.put("format", JSONObject(it)) }

  // Ollama's switch is a plain boolean with no budget - the server owns the model, so how long
  // it is allowed to think is its configuration rather than this app's. Sent only when thinking
  // was asked for: an older server that does not know the field rejects the request outright,
  // so a turn that wants no thinking must not mention it.
  if (thinkingFor(request.reasoning, structured = request.responseSchema != null) != null) {
    body.put("think", true)
  }

  return body.toString()
}

private fun wireRole(role: Message.Role): String =
  when (role) {
    Message.Role.USER -> "user"
    Message.Role.ASSISTANT -> "assistant"
    Message.Role.SYSTEM -> "system"
    Message.Role.TOOL -> "tool"
  }

/**
 * The request body for an OpenAI-compatible `POST /v1/chat/completions`.
 *
 * vLLM and Unsloth both speak this, which is why they share a backend. What differs between them
 * is what a person types, not what goes on the wire.
 */
internal fun openAiRequestBody(
  model: String,
  request: TurnRequest,
  sampling: Sampling = Sampling(),
): String {
  val messages = JSONArray()
  if (request.systemPrompt.isNotBlank()) {
    messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
  }
  for (message in request.messages) {
    val pictures = message.attachments.filterIsInstance<Attachment.Picture>()
    messages.put(
      JSONObject()
        .put("role", wireRole(message.role))
        .put(
          "content",
          // A string when there is only text, which is what every server takes. The list form
          // is used only when there is a picture to put beside it - some servers reject a
          // content list on a plain message, and there is nothing to gain by risking it.
          if (pictures.isEmpty()) message.text
          else
            JSONArray().apply {
              pictures.forEach { picture ->
                put(
                  JSONObject()
                    .put("type", "image_url")
                    .put(
                      "image_url",
                      JSONObject().put("url", "data:image/jpeg;base64," + base64(picture.bytes)),
                    )
                )
              }
              put(JSONObject().put("type", "text").put("text", message.text))
            },
        )
        .apply {
          // A tool result has to say which tool, and OpenAI's field for that is `name`.
          if (message.toolName.isNotEmpty()) put("name", message.toolName)
        }
    )
  }

  val body =
    JSONObject()
      .put("model", model)
      .put("messages", messages)
      .put("stream", true)
      .apply { openAiSampling(this, samplingFor(sampling, request, request.responseSchema != null)) }

  if (request.tools.isNotEmpty()) {
    val tools = JSONArray()
    for (tool in request.tools) {
      tools.put(
        JSONObject()
          .put("type", "function")
          .put(
            "function",
            JSONObject()
              .put("name", tool.name)
              .put("description", tool.description)
              .put("parameters", JSONObject(tool.parametersSchema)),
          )
      )
    }
    body.put("tools", tools)
  }

  // Structured output, in the form both of these take: a named schema under response_format.
  // `strict` is what makes it a constraint rather than a suggestion on servers that honour it.
  request.responseSchema?.let { schema ->
    body.put(
      "response_format",
      JSONObject()
        .put("type", "json_schema")
        .put(
          "json_schema",
          JSONObject()
            .put("name", "answer")
            .put("schema", JSONObject(schema))
            .put("strict", true),
        ),
    )
  }

  // Thinking, which is not an OpenAI field at all: both of these pass extra arguments into the
  // model's chat template, and that is where the switch lives.
  //
  // Sent on a structured turn too, as false, and that is the one place this says something it
  // could have left out. vLLM has a known bug where a schema is not enforced while a reasoning
  // parser is active - the output escapes into the unconstrained reasoning channel - and
  // turning thinking off explicitly is the documented way around it. This app never thinks on a
  // structured turn anyway, so saying so costs nothing and buys a working schema.
  val thinking = thinkingFor(request.reasoning, structured = request.responseSchema != null)
  if (thinking != null || request.responseSchema != null) {
    body.put(
      "chat_template_kwargs",
      JSONObject().put("enable_thinking", thinking != null),
    )
  }

  return body.toString()
}

/**
 * Reads one line of an OpenAI-style stream, or null if there is nothing in it worth acting on.
 *
 * Server-sent events rather than Ollama's bare JSON lines: each payload arrives as `data: {…}`,
 * blank lines separate events, and the stream ends with a literal `data: [DONE]`.
 *
 * Deltas, not snapshots. A chat-completions chunk carries `choices[0].delta`, which holds only
 * what is new - so the caller appends, exactly as it does for Ollama.
 */
internal fun readOpenAiChunk(line: String): ChatChunk? {
  val trimmed = line.trim()
  if (trimmed.isEmpty()) return null

  // An error comes back as a plain JSON object rather than an SSE event, so it is tried first.
  if (!trimmed.startsWith(SSE_DATA)) {
    val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    val error = errorTextOf(json)
    return if (error.isEmpty()) null else ChatChunk(error = error)
  }

  val payload = trimmed.removePrefix(SSE_DATA).trim()
  if (payload == SSE_DONE) return ChatChunk(done = true)

  val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
  errorTextOf(json).takeIf { it.isNotEmpty() }?.let {
    return ChatChunk(error = it)
  }

  val choice = json.optJSONArray("choices")?.optJSONObject(0)
  val delta = choice?.optJSONObject("delta") ?: choice?.optJSONObject("message")

  return ChatChunk(
    text = delta?.optString("content").orEmpty(),
    // `finish_reason` is how a chat completion says it is finished. The [DONE] line follows,
    // but not every server sends one - so the reply must not depend on it arriving.
    done = choice?.optString("finish_reason").orEmpty().let { it.isNotEmpty() && it != "null" },
    toolCalls = delta?.let(::readOpenAiToolCalls).orEmpty(),
    // Both names, because the field was renamed. Older vLLM returns `reasoning_content`; newer
    // examples use `reasoning`. Reading one would silently show no thinking on half the
    // versions in the wild, and which half would depend on when somebody installed it.
    thinking =
      delta?.optString("reasoning_content").orEmpty().ifEmpty {
        delta?.optString("reasoning").orEmpty()
      },
  )
}

/** An error object's message, wherever this family of servers put it. */
private fun errorTextOf(json: JSONObject): String {
  json.optJSONObject("error")?.let { error ->
    return error.optString("message").ifEmpty { error.toString() }
  }
  return json.optString("error")
}

/**
 * Tool calls on one delta, in OpenAI's shape: `tool_calls[].function.{name,arguments}`.
 *
 * `arguments` is a JSON *string* here, not an object - that is the format, and parsing it is not
 * optional. A call whose arguments will not parse is passed on with none rather than dropped:
 * the loop then runs the tool and the tool says what it needed, which is a better failure than
 * a model that asked for something and was ignored.
 *
 * Streamed tool calls arrive in fragments across deltas on some servers. One whose name has not
 * arrived yet is skipped, because a call with no name cannot be looked up - the delta that
 * carries the name is the one that counts.
 */
internal fun readOpenAiToolCalls(delta: JSONObject): List<ToolInvocation> {
  val calls = delta.optJSONArray("tool_calls") ?: return emptyList()
  return (0 until calls.length()).mapNotNull { index ->
    val function = calls.optJSONObject(index)?.optJSONObject("function") ?: return@mapNotNull null
    val name = function.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val raw = function.optString("arguments")
    val arguments = runCatching { JSONObject(raw) }.getOrNull()
    ToolInvocation(
      name = name,
      arguments = arguments?.keys()?.asSequence()?.associateWith { arguments.get(it) }.orEmpty(),
    )
  }
}

private const val SSE_DATA = "data:"

private const val SSE_DONE = "[DONE]"

/**
 * Bytes as base64, on one line.
 *
 * The JDK's encoder rather than `android.util.Base64`. That one arrived at API 26 and this
 * app's minimum is 31, so there is nothing to work around - and the Android class is a stub
 * in unit tests that returns null, which would leave the one part of this that most needs
 * checking untestable.
 *
 * The basic encoder, not the MIME one: MIME inserts a newline every seventy-six characters,
 * which is valid base64 and invalid JSON.
 */
private fun base64(bytes: ByteArray): String =
  java.util.Base64.getEncoder().encodeToString(bytes)
