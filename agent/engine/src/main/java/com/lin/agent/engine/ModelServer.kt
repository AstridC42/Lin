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
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** One model the server already has pulled, as it describes itself. */
data class ServerModel(
  /** What the server calls it, tag included: `qwen3:32b`. This is what a request names. */
  val name: String,
  val sizeBytes: Long,
  /** `32.8B`, or empty if the server did not say. */
  val parameterSize: String,
  /** `Q4_K_M`, or empty. */
  val quantization: String,
)

/** What asking the server for its models produced. */
sealed interface ServerModelsResult {
  /** The server answered. May be empty: reachable, with nothing pulled. */
  data class Found(val models: List<ServerModel>) : ServerModelsResult

  /** [message] is written for the person holding the phone, not for a log. */
  data class Failed(val message: String) : ServerModelsResult
}

/**
 * Asks an Ollama server what it has.
 *
 * The first thing in this app that talks to the server at all, and deliberately the smallest
 * useful thing: listing models proves the address, the tunnel and the header all work, which is
 * the question that has to be answered before anything can be delegated. A `GET /api/tags` is
 * also harmless - it starts no inference and loads no weights.
 *
 * Every failure comes back as a sentence rather than an exception class. That is most of the value
 * here: "could not find that address" and "the server refused the request" and "something answered
 * but not like Ollama" are three different things to go and fix, and a phone has no logcat.
 *
 * [openConnection] is injectable so all of this is testable without a server.
 */
class ModelServer(
  private val openConnection: (url: String, headers: Map<String, String>) -> Response =
    ::httpConnection
) {

  /** The small surface of an HTTP response this needs, so a test can supply one. */
  interface Response : AutoCloseable {
    val status: Int

    /** The whole body. These answers are kilobytes, so there is nothing to stream. */
    val body: String
  }

  /**
   * Lists what the server has pulled.
   *
   * [typedUrl] is taken as the user typed it, not as a validated URL, because this is called from
   * a text field they are still editing - checking an address before saving it is the point.
   */
  suspend fun models(
    typedUrl: String,
    authHeader: String = "",
    kind: ServerKind = ServerKind.OLLAMA,
  ): ServerModelsResult =
    withContext(Dispatchers.IO) {
      val url =
        serverUrl(typedUrl, kind, Endpoint.MODELS)
          ?: return@withContext ServerModelsResult.Failed(NO_ADDRESS)
      try {
        openConnection(url, headersFrom(authHeader)).use { response ->
          if (response.status != HTTP_OK) {
            return@withContext ServerModelsResult.Failed(describeStatus(response.status))
          }
          ServerModelsResult.Found(parseModels(response.body, kind))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: JSONException) {
        Log.w(TAG, "Not a $kind answer from $url", e)
        ServerModelsResult.Failed(wrongDialect(kind))
      } catch (e: Throwable) {
        Log.w(TAG, "Could not reach $url", e)
        ServerModelsResult.Failed(describeFailure(e))
      }
    }

  companion object {
    private const val TAG = "LinModelServer"
    private const val HTTP_OK = 200

    /** Interactive, unlike a model download: somebody is watching this one happen. */
    private const val TIMEOUT_MS = 10_000

    internal const val NO_ADDRESS = "Set the server address above first."

    internal const val NOT_OLLAMA =
      "Something answered at that address, but not the way Ollama does. Check the port - Ollama " +
        "listens on 11434 - and that the address does not point at another service."

    /**
     * Something answered, but not in the dialect that was chosen.
     *
     * Named per kind because the fix differs and the symptom does not. Pointing the Ollama
     * setting at a vLLM server and pointing it at a router both produce "that is not Ollama",
     * and only one of them is fixed by changing the port.
     */
    internal fun wrongDialect(kind: ServerKind): String =
      when (kind) {
        ServerKind.OLLAMA -> NOT_OLLAMA
        ServerKind.VLLM ->
          "Something answered there, but not the way vLLM does. vLLM listens on 8000 and serves " +
            "/v1 - check the port, and that the kind of server above matches what is running."
        ServerKind.UNSLOTH ->
          "Something answered there, but not the way Unsloth does. Check the port Unsloth " +
            "printed, and that the API key is in the header field above."
      }

    private fun httpConnection(url: String, headers: Map<String, String>): Response {
      val connection =
        (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          instanceFollowRedirects = true
          connectTimeout = TIMEOUT_MS
          readTimeout = TIMEOUT_MS
          headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
      val status = connection.responseCode
      // errorStream for anything but 2xx: inputStream throws there, and the body of a refusal is
      // often the most informative thing available - a proxy's login page, say.
      val stream: InputStream? =
        if (status in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      return object : Response {
        override val status: Int = status
        override val body: String = text

        override fun close() = connection.disconnect()
      }
    }
  }
}

/**
 * The address of one Ollama endpoint, built out of whatever the user typed, or null if they typed
 * nothing.
 *
 * All of the forgiveness lives here, because this field is typed on a phone keyboard and every one
 * of these mistakes looks identical to the server being down:
 *
 *  - no scheme, which is what typing a LAN address from memory produces
 *  - a trailing slash, which is what a browser's address bar hands over
 *  - the API path already attached, which is what pasting from Ollama's own documentation gives
 *  - no port on a plain-http address, which almost always means the default
 *
 * The scheme default is `http`, not `https`: an address with no scheme is nearly always a LAN
 * address or an IP, and Ollama serves plain http. A tunnel hostname gets pasted with its scheme.
 *
 * The port default is only applied to `http`. Under `https` something is terminating TLS - a
 * tunnel or a reverse proxy - and that listens on 443, not on Ollama's port.
 */
internal fun apiUrl(typed: String, endpoint: String): String? {
  var text = typed.trim()
  if (text.isEmpty()) return null
  if (!text.contains("://")) text = "http://$text"

  text = text.trimEnd('/')
  // A pasted `.../api/tags` or `.../api/chat` or `.../api` would otherwise be asked for twice
  // over. Any endpoint, not just this call's: the address field is shared between them.
  text = text.replace(API_SUFFIX, "").trimEnd('/')

  val uri = runCatching { URI(text) }.getOrNull() ?: return null
  val host = uri.host ?: return null
  val base =
    if (uri.port == -1 && uri.scheme.equals("http", ignoreCase = true)) {
      "${uri.scheme}://$host:$OLLAMA_DEFAULT_PORT${uri.path.orEmpty()}"
    } else {
      text
    }
  return "$base/api/$endpoint"
}

/** What the server has pulled. */
internal fun tagsUrl(typed: String): String? = apiUrl(typed, "tags")

/** Where a conversation is sent. */
internal fun chatUrl(typed: String): String? = apiUrl(typed, "chat")

/**
 * The headers to send, parsed from one free-text field as `Name: value` per line.
 *
 * One per line rather than one in total, and that is not generality for its own sake: a Cloudflare
 * Access service token - the way this server gets reached from outside - is two headers,
 * `CF-Access-Client-Id` and `CF-Access-Client-Secret`. A field that held one would have made the
 * case it was added for the one case it could not serve.
 *
 * Anything unparseable is dropped rather than guessed at. A line with no colon is not a header,
 * and sending it as one would produce a failure about the server rather than about the typing.
 */
internal fun headersFrom(field: String): Map<String, String> =
  field
    .lineSequence()
    .mapNotNull { line ->
      val trimmed = line.trim()
      val colon = trimmed.indexOf(':')
      if (colon <= 0) return@mapNotNull null
      val name = trimmed.take(colon).trim()
      val value = trimmed.substring(colon + 1).trim()
      if (name.isEmpty() || value.isEmpty()) null else name to value
    }
    .toMap()

/**
 * Reads Ollama's `/api/tags` answer.
 *
 * Lenient about what it finds and strict about what it returns: a model with no usable name is
 * dropped, and the size and the details are optional, because a picker that cannot show a
 * parameter count is still a working picker while one that throws on an unfamiliar field is not.
 * Sorted by name so the list does not reshuffle between refreshes.
 *
 * Throws [JSONException] when the body is not an object at all, which is the signal that whatever
 * answered is not Ollama.
 */
internal fun parseModels(body: String, kind: ServerKind = ServerKind.OLLAMA): List<ServerModel> =
  if (kind.speaksOpenAi) parseOpenAiModels(body) else parseOllamaModels(body)

/**
 * The models an OpenAI-compatible server lists, from `GET /v1/models`.
 *
 * Only the id is read, and that is the format rather than a shortcut: OpenAI's list says nothing
 * about parameter count, quantisation or size on disk, which Ollama's does. Those fields come
 * back empty and the screen shows a name alone - honest, and better than inventing numbers for a
 * model served from a directory of safetensors where "size" is not a meaningful single figure.
 *
 * A `data` array is what makes this an OpenAI answer. Its absence throws, like the Ollama
 * parser's, so the caller reports a wrong dialect rather than an empty server.
 */
internal fun parseOpenAiModels(body: String): List<ServerModel> {
  val models = JSONObject(body).getJSONArray("data")
  return (0 until models.length())
    .mapNotNull { index ->
      val entry = models.optJSONObject(index) ?: return@mapNotNull null
      val name = entry.optString("id").ifEmpty { entry.optString("name") }
      if (name.isEmpty()) return@mapNotNull null
      ServerModel(name = name, sizeBytes = 0L, parameterSize = "", quantization = "")
    }
    .sortedBy { it.name }
}

internal fun parseOllamaModels(body: String): List<ServerModel> {
  val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
  return (0 until models.length())
    .mapNotNull { index ->
      val entry = models.optJSONObject(index) ?: return@mapNotNull null
      // `name` is what the API calls it; `model` is the same string on current versions, and the
      // fallback costs one line against a field rename.
      val name = entry.optString("name").ifEmpty { entry.optString("model") }
      if (name.isEmpty()) return@mapNotNull null
      val details = entry.optJSONObject("details")
      ServerModel(
        name = name,
        sizeBytes = entry.optLong("size", 0L),
        parameterSize = details?.optString("parameter_size").orEmpty(),
        quantization = details?.optString("quantization_level").orEmpty(),
      )
    }
    .sortedBy { it.name }
}

/** What a non-200 means, in terms of what to go and change. */
internal fun describeStatus(status: Int): String =
  when (status) {
    401,
    403 ->
      "The server refused the request ($status). If it is behind Cloudflare Access or another " +
        "proxy, the header field above is what it checks."
    404 ->
      "Reached that address, but there is no Ollama API there (404). Check the port and the path."
    in 500..599 ->
      "Reached that address and it returned an error ($status). If it is behind a tunnel, the " +
        "tunnel is up but Ollama behind it may not be."
    else -> "The server answered with status $status."
  }

/** What a failed connection means, in terms of what to go and change. */
internal fun describeFailure(error: Throwable): String =
  when (error) {
    is UnknownHostException ->
      "Could not find that address. Check the hostname, and that this phone is on a network " +
        "that can resolve it."
    is SocketTimeoutException ->
      "No answer within ten seconds. On the home network that usually means the wrong address; " +
        "through a tunnel it can mean the tunnel is down."
    is ConnectException,
    is NoRouteToHostException ->
      // The single most common cause, and invisible from the phone: Ollama binds to localhost
      // unless told otherwise, so it is running and simply will not accept a connection.
      "Nothing accepted the connection. If Ollama is running on that machine, it may only be " +
        "listening to itself - it needs OLLAMA_HOST=0.0.0.0 to answer other devices."
    is SSLException ->
      "The secure connection failed. If the address is https, check the certificate; if the " +
        "server is plain http, use http in the address."
    else -> error.message?.trim()?.takeIf { it.isNotEmpty() } ?: error::class.java.simpleName
  }

private const val OLLAMA_DEFAULT_PORT = 11434

/** A trailing `/api` or `/api/<endpoint>`, which is what pasting from the docs leaves. */
private val API_SUFFIX = Regex("/api(/[A-Za-z]+)?$")
