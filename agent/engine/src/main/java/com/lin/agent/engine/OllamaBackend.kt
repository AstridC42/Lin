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
class OllamaBackend(
  private val baseUrl: String,
  private val authHeader: String,
  private val modelName: String,
  private val server: OllamaServer = OllamaServer(),
  private val openStream: (url: String, headers: Map<String, String>, body: String) -> Stream =
    ::httpStream,
) : LlmBackend {

  /** A streamed response, one JSON object per line, as Ollama sends them. */
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
      // Both reported false on purpose rather than optimistically. Ollama supports tool calling
      // and vision for *some* models, and nothing here knows which - claiming a capability the
      // chosen model does not have would fail at the point of use, where it is hardest to
      // explain. They turn on when there is a loop that can verify them.
      supportsTools = false,
      supportsImages = false,
      supportsAudio = false,
      // Zero means "the server's own setting". It is configured per model on the server, this
      // app does not override it, and inventing a number here would only be wrong.
      contextWindow = UNKNOWN_CONTEXT,
    )

  /**
   * Whether the server is there *and* still has this model.
   *
   * Both halves matter. A server that is reachable but no longer has the chosen model would fail
   * every turn with a 404 that reads like the server being broken, when what actually happened is
   * that somebody removed a model.
   */
  override suspend fun reachable(): Boolean =
    when (val result = server.models(baseUrl, authHeader)) {
      is ServerModelsResult.Found -> result.models.any { it.name == modelName }
      is ServerModelsResult.Failed -> false
    }

  override fun converse(request: TurnRequest): Flow<TurnEvent> =
    flow {
        val url = chatUrl(baseUrl)
        if (url == null) {
          emit(TurnEvent.Failed(OllamaServer.NO_ADDRESS))
          return@flow
        }

        val answer = StringBuilder()
        var understoodAnything = false
        try {
          openStream(url, headersFrom(authHeader), chatRequestBody(modelName, request)).use { s ->
            if (s.status != HTTP_OK) {
              emit(TurnEvent.Failed(describeStatus(s.status), s.errorBody.take(DETAIL_CHARS)))
              return@use
            }
            for (line in s.lines) {
              val chunk = readChunk(line) ?: continue
              understoodAnything = true
              if (chunk.error.isNotEmpty()) {
                emit(TurnEvent.Failed(serverSaid(chunk.error), chunk.error))
                return@use
              }
              if (chunk.text.isNotEmpty()) {
                answer.append(chunk.text)
                emit(TurnEvent.Token(chunk.text))
              }
              if (chunk.done) {
                emit(TurnEvent.Done(answer.toString()))
                return@use
              }
            }
            // The stream ended without the done flag. Keeping whatever arrived is better than
            // discarding a reply that was merely cut short.
            when {
              answer.isNotEmpty() -> emit(TurnEvent.Done(answer.toString()))
              !understoodAnything -> emit(TurnEvent.Failed(OllamaServer.NOT_OLLAMA))
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
    private const val TAG = "LinOllamaBackend"
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

  return ChatChunk(
    text = json.optJSONObject("message")?.optString("content").orEmpty(),
    done = json.optBoolean("done", false),
  )
}

/**
 * The request body for `POST /api/chat`.
 *
 * Built with JSONObject rather than string templates, because a conversation contains quotes and
 * newlines and an escaping mistake here would corrupt whatever the user actually typed.
 *
 * No `num_ctx`: the context window is the server's own configuration, per model, and this app
 * has no business overriding what its owner set.
 */
internal fun chatRequestBody(model: String, request: TurnRequest): String {
  val messages = JSONArray()
  if (request.systemPrompt.isNotBlank()) {
    messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
  }
  for (message in request.messages) {
    messages.put(JSONObject().put("role", wireRole(message.role)).put("content", message.text))
  }
  return JSONObject()
    .put("model", model)
    .put("messages", messages)
    .put("stream", true)
    .put(
      "options",
      JSONObject().put("temperature", request.temperature).put("top_k", request.topK),
    )
    .toString()
}

private fun wireRole(role: Message.Role): String =
  when (role) {
    Message.Role.USER -> "user"
    Message.Role.ASSISTANT -> "assistant"
    Message.Role.SYSTEM -> "system"
  }
