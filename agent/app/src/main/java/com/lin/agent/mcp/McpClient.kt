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

package com.lin.agent.mcp

import android.util.Log
import com.lin.agent.engine.mcp.LEGACY_VERSION
import com.lin.agent.engine.mcp.MODERN_VERSION
import com.lin.agent.engine.mcp.McpOutcome
import com.lin.agent.engine.mcp.McpServer
import com.lin.agent.engine.mcp.RemoteTool
import com.lin.agent.engine.mcp.bestModernVersion
import com.lin.agent.engine.mcp.errorIn
import com.lin.agent.engine.mcp.initializeParams
import com.lin.agent.engine.mcp.legacyNotification
import com.lin.agent.engine.mcp.legacyRequest
import com.lin.agent.engine.mcp.looksLikeModernError
import com.lin.agent.engine.mcp.modernRequest
import com.lin.agent.engine.mcp.outcomeFrom
import com.lin.agent.engine.mcp.responseFrom
import com.lin.agent.engine.mcp.toolsFrom
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One MCP server, spoken to over HTTP.
 *
 * The protocol work is in `engine/mcp`, which is pure and tested; this is the HTTP around it
 * and the one piece of state that cannot be pure - which era this particular server speaks.
 *
 * **Two eras, worked out once.** Revision `2026-07-28` dropped the `initialize` handshake and
 * protocol-level sessions: every request carries its own version, identity and capabilities.
 * Everything before it opens with `initialize` and keeps a session id. Most servers deployed
 * today are the older kind, so Lin tries modern first and falls back - and caches the answer,
 * because the era is a property of the server rather than of a request, and probing on every
 * call would double the traffic.
 */
class McpClient(val server: McpServer) {

  private enum class Era {
    UNKNOWN,
    MODERN,
    LEGACY,
  }

  @Volatile private var era = Era.UNKNOWN

  /** Only ever set on a legacy server; the modern protocol has no sessions. */
  @Volatile private var session: String? = null

  @Volatile private var handshakeDone = false

  /** The version this server accepted, which may be older than the one Lin asked for. */
  @Volatile private var agreedVersion = MODERN_VERSION

  private val nextId = AtomicInteger(1)

  /** What the server offers. Empty is a valid answer; a failure throws. */
  suspend fun tools(): List<RemoteTool> = toolsFrom(request("tools/list", JSONObject()))

  suspend fun call(toolName: String, arguments: Map<String, Any?>): McpOutcome {
    val params =
      JSONObject().put("name", toolName).put("arguments", JSONObject(arguments.toMutableMap()))
    return outcomeFrom(request("tools/call", params))
  }

  /**
   * One request, with whatever era-handling it needs.
   *
   * Throws on failure rather than returning a result type: every caller of this turns a
   * failure into a sentence for the model or a line on a settings screen, and both of those
   * read better from one catch than from unwrapping at each step.
   */
  private suspend fun request(method: String, params: JSONObject): JSONObject {
    if (era == Era.LEGACY) return legacyCall(method, params)

    val attempt = post(modernRequest(nextId.getAndIncrement(), method, params, version = agreedVersion))

    if (attempt.ok) {
      era = Era.MODERN
      return resultOf(attempt.body, method)
    }

    // A modern server answers an unsupported version with 400 and says what it does support.
    if (attempt.status == 400 && looksLikeModernError(attempt.body)) {
      era = Era.MODERN
      val error = responseFrom(attempt.body)?.let { errorIn(it) }
      // A server that lists only handshake-era versions is dual-era and wants the handshake:
      // "2025-11-25" in a modern request's _meta is not a thing, because that version is the
      // handshake. Taking the first entry of the list would retry modern against it forever.
      if (error != null && error.supported.isNotEmpty() && bestModernVersion(error.supported) == null) {
        era = Era.LEGACY
        return legacyCall(method, params)
      }
      val better = bestModernVersion(error?.supported.orEmpty())
      if (better != null && better != agreedVersion) {
        agreedVersion = better
        val second =
          post(modernRequest(nextId.getAndIncrement(), method, params, version = agreedVersion))
        if (second.ok) return resultOf(second.body, method)
        throw McpFailure(describe(second, method))
      }
      throw McpFailure(describe(attempt, method))
    }

    // Anything else in the 4xx range, with no recognised modern error in it, is the shape of
    // a server from before the handshake was removed. 404 and 405 are in here because that
    // is what a server hosting only the older endpoints answers a modern POST with.
    if (era == Era.UNKNOWN && attempt.status in FALLBACK_STATUSES) {
      Log.i(TAG, "${server.url} answered ${attempt.status}; trying the handshake protocol")
      era = Era.LEGACY
      return legacyCall(method, params)
    }

    throw McpFailure(describe(attempt, method))
  }

  /** The handshake era: initialize once, then ordinary calls carrying the session. */
  private suspend fun legacyCall(method: String, params: JSONObject): JSONObject {
    handshake()
    val attempt = post(legacyRequest(nextId.getAndIncrement(), method, params))
    if (!attempt.ok) throw McpFailure(describe(attempt, method))
    return resultOf(attempt.body, method)
  }

  @Synchronized
  private fun markHandshakeStarted(): Boolean {
    if (handshakeDone) return false
    handshakeDone = true
    return true
  }

  private suspend fun handshake() {
    if (!markHandshakeStarted()) return
    val attempt =
      post(legacyRequest(nextId.getAndIncrement(), "initialize", initializeParams()))
    if (!attempt.ok) {
      handshakeDone = false
      throw McpFailure(describe(attempt, "initialize"))
    }
    // The session id, if the server minted one. Required on every later request by servers
    // that use them, and absent on those that do not - both are allowed.
    session = attempt.sessionId
    // Told that the client is ready. A notification, so there is no answer to wait for, and a
    // server that refuses it is not worth failing the connection over.
    runCatching { post(legacyNotification("notifications/initialized")) }
  }

  private fun resultOf(body: String, method: String): JSONObject {
    val envelope =
      responseFrom(body) ?: throw McpFailure("$method: the server sent nothing readable back")
    errorIn(envelope)?.let { throw McpFailure("$method: ${it.message}") }
    return envelope.optJSONObject("result")
      ?: throw McpFailure("$method: the server's answer had no result in it")
  }

  private fun describe(attempt: Attempt, method: String): String {
    val said = responseFrom(attempt.body)?.let { errorIn(it) }?.message
    return if (said != null) "$method: $said"
    else "$method: the server answered ${attempt.status}"
  }

  private data class Attempt(
    val status: Int,
    val body: String,
    val sessionId: String?,
  ) {
    val ok: Boolean
      get() = status in 200..299
  }

  private suspend fun post(request: com.lin.agent.engine.mcp.McpRequest): Attempt =
    withContext(Dispatchers.IO) {
      val connection =
        (URL(server.url).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          doOutput = true
          connectTimeout = CONNECT_MS
          readTimeout = READ_MS
          setRequestProperty("Content-Type", "application/json")
          // Both, because a server may answer either and a client must take both. Asking for
          // only JSON would have a streaming server refuse the request outright.
          setRequestProperty("Accept", "application/json, text/event-stream")
          request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
          session?.let { setRequestProperty("Mcp-Session-Id", it) }
          server.authHeader()?.let { (name, value) -> setRequestProperty(name, value) }
        }
      try {
        connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        Attempt(status, body, connection.getHeaderField("Mcp-Session-Id"))
      } finally {
        connection.disconnect()
      }
    }

  private companion object {
    const val TAG = "LinMcp"
    const val CONNECT_MS = 10_000

    /**
     * Long enough for a tool that does real work, short enough that a dead server does not
     * hold a turn open. A model server gets three minutes because it is generating; an MCP
     * tool is usually a lookup.
     */
    const val READ_MS = 60_000

    /** The statuses a pre-handshake-removal server answers a modern POST with. */
    val FALLBACK_STATUSES = setOf(400, 404, 405, 406, 415)
  }
}

/** A connection or protocol failure, phrased for a person or a model to read. */
class McpFailure(message: String) : Exception(message)
