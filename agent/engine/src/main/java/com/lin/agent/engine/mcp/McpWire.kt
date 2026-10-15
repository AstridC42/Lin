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

package com.lin.agent.engine.mcp

import org.json.JSONObject

/**
 * What goes on the wire to an MCP server, and what comes back.
 *
 * Everything in this file is a string in and a string out, because that is the half of an MCP
 * client that can be got wrong silently and the half that needs no network to test. The HTTP
 * itself lives in the app module and is four lines around these.
 *
 * **Two eras.** Revision `2026-07-28` removed the `initialize` handshake and protocol-level
 * sessions entirely: every request now carries its own protocol version, client identity and
 * capabilities in `params._meta`, and the server answers each one independently. Everything
 * `2025-11-25` and earlier opens with `initialize`, gets a session id back in a header, and
 * keeps it. Most servers deployed today are the older kind, so Lin speaks both and works out
 * which on the first request - see [looksLikeModernError], which is the whole of that decision.
 */

/** The revision Lin asks for first. */
const val MODERN_VERSION = "2026-07-28"

/**
 * What Lin falls back to.
 *
 * `2025-06-18` rather than the last handshake revision: it is the one where the
 * `MCP-Protocol-Version` header was introduced and is the most widely implemented of the
 * handshake-era versions. A server newer than it still accepts it.
 */
const val LEGACY_VERSION = "2025-06-18"

const val CLIENT_NAME = "Lin"

/** One request, ready to post. */
data class McpRequest(val body: String, val headers: Map<String, String>)

/**
 * A modern request: the protocol metadata is in the body, mirrored into headers.
 *
 * The mirroring is not optional and not cosmetic. A server **must** reject a request whose
 * `MCP-Protocol-Version` header disagrees with `_meta`, or whose `Mcp-Name` disagrees with
 * `params.name`, with a `HeaderMismatch` error - the point being that a load balancer routing
 * on the header and a server executing on the body must not be able to disagree. So the
 * headers are built from the same values as the body, here, rather than by the caller.
 */
fun modernRequest(
  id: Int,
  method: String,
  params: JSONObject = JSONObject(),
  clientVersion: String = "1.0.0",
  version: String = MODERN_VERSION,
): McpRequest {
  val meta =
    JSONObject()
      .put("io.modelcontextprotocol/protocolVersion", version)
      .put(
        "io.modelcontextprotocol/clientInfo",
        JSONObject().put("name", CLIENT_NAME).put("version", clientVersion),
      )
      // Empty: Lin consumes tools and offers the server nothing back. Sampling, elicitation
      // and roots are all things a server would ask the phone to do, and none of them is
      // something to hand a remote server without a lot more thought than a capability flag.
      .put("io.modelcontextprotocol/clientCapabilities", JSONObject())

  val withMeta = JSONObject(params.toString()).put("_meta", meta)
  val body =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", id)
      .put("method", method)
      .put("params", withMeta)
      .toString()

  val headers = mutableMapOf("MCP-Protocol-Version" to version, "Mcp-Method" to method)
  // Required for tools/call, resources/read and prompts/get, and only meaningful when the
  // body actually carries the name.
  val name = params.optString("name").ifEmpty { params.optString("uri") }
  if (name.isNotEmpty()) headers["Mcp-Name"] = encodeHeaderValue(name)
  return McpRequest(body, headers)
}

/** A handshake-era request. No `_meta`; the version still goes in the header from 2025-06-18. */
fun legacyRequest(
  id: Int,
  method: String,
  params: JSONObject = JSONObject(),
  version: String = LEGACY_VERSION,
): McpRequest =
  McpRequest(
    body =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", params)
        .toString(),
    headers = mapOf("MCP-Protocol-Version" to version),
  )

/**
 * A notification: a message with no id, which the server answers with 202 and nothing else.
 *
 * Its own function rather than a request with the id stripped out afterwards. A JSON-RPC
 * message with an `id` of any value is a request, and a server that answers one it was not
 * expecting leaves the client waiting for a response that never comes.
 */
fun legacyNotification(method: String, params: JSONObject = JSONObject()): McpRequest =
  McpRequest(
    body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).toString(),
    headers = mapOf("MCP-Protocol-Version" to LEGACY_VERSION),
  )

/** The `initialize` params a handshake-era server expects. */
fun initializeParams(clientVersion: String = "1.0.0", version: String = LEGACY_VERSION): JSONObject =
  JSONObject()
    .put("protocolVersion", version)
    .put("capabilities", JSONObject())
    .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", clientVersion))

/**
 * A header value, escaped if it cannot go in a header as it stands.
 *
 * HTTP field values are visible ASCII, space and tab. A tool name is only *recommended* to
 * stay inside that set, so one with an accent or an emoji in it has to be carried as
 * `=?base64?...?=` - and a plain value that happens to look like that sentinel has to be
 * encoded too, or the server would decode something that was never encoded.
 */
fun encodeHeaderValue(value: String): String {
  val plain = value.all { it.code in 0x21..0x7E || it == ' ' || it == '\t' }
  val padded = value != value.trim()
  val sentinel = value.startsWith(SENTINEL_OPEN) && value.endsWith(SENTINEL_CLOSE)
  if (plain && !padded && !sentinel) return value
  return SENTINEL_OPEN + base64(value.toByteArray(Charsets.UTF_8)) + SENTINEL_CLOSE
}

/**
 * The JSON-RPC envelope in a response body, however it was framed.
 *
 * A server may answer a request with one JSON object or with an SSE stream carrying
 * notifications and then the response, and a client **must** accept both. Since Lin makes one
 * request at a time and waits for its answer, the stream can be read to the end and the last
 * message carrying `result` or `error` taken - progress notifications on the way have no `id`
 * and are skipped, and so are SSE comment lines, which keep-alives are made of.
 *
 * Null when there is no response in there at all, which a caller must treat as a failure
 * rather than as an empty answer.
 */
fun responseFrom(body: String): JSONObject? {
  val trimmed = body.trim()
  if (trimmed.isEmpty()) return null
  if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()

  // SSE: `data:` lines, one event per blank-line-separated block, comments starting with ':'.
  var last: JSONObject? = null
  val payload = StringBuilder()
  fun endEvent() {
    if (payload.isNotEmpty()) {
      runCatching { JSONObject(payload.toString()) }
        .getOrNull()
        ?.takeIf { it.has("result") || it.has("error") }
        ?.let { last = it }
      payload.clear()
    }
  }
  for (raw in trimmed.lines()) {
    val line = raw.trimEnd('\r')
    when {
      line.isEmpty() -> endEvent()
      // Written out although the `else` below would swallow it too, because a reader
      // checking "are keep-alives handled?" should find the answer here rather than have to
      // reason about what falls through. Servers are encouraged to send a bare colon line
      // during quiet periods so intermediaries do not close the stream.
      line.startsWith(":") -> Unit
      line.startsWith("data:") -> {
        // Multi-line data is joined with newlines, per the SSE spec.
        if (payload.isNotEmpty()) payload.append('\n')
        payload.append(line.removePrefix("data:").removePrefix(" "))
      }
      else -> Unit
    }
  }
  endEvent()
  return last
}

/** What a server said went wrong. */
data class McpError(val code: Int, val message: String, val supported: List<String> = emptyList())

/** The error in an envelope, or null if it carries a result. */
fun errorIn(envelope: JSONObject): McpError? {
  val error = envelope.optJSONObject("error") ?: return null
  val supported =
    error.optJSONObject("data")?.optJSONArray("supported")?.let { array ->
      (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
    }
  return McpError(
    code = error.optInt("code", 0),
    message = error.optString("message").ifEmpty { "the server did not say why" },
    supported = supported.orEmpty(),
  )
}

/**
 * Whether a 400 came from a server that speaks the modern protocol.
 *
 * This one call decides which era Lin is talking to, and the spec is specific about it: a
 * modern server answers an unsupported version, a missing capability or a header mismatch
 * with `400` *and a recognised JSON-RPC error*, so a `400` carrying one of those means "retry
 * properly", while a `400` carrying anything else - including nothing - means the server is
 * the handshake kind and Lin should send `initialize`.
 *
 * Getting this backwards is not a visible failure, which is why it is a tested function: read
 * as legacy, a modern server is sent an `initialize` it does not implement; read as modern, a
 * legacy server is sent `_meta` it ignores and headers it rejects.
 */
fun looksLikeModernError(body: String): Boolean {
  val envelope = responseFrom(body) ?: return false
  val error = errorIn(envelope) ?: return false
  return error.code in MODERN_ERROR_CODES
}

/**
 * The newest version in [supported] that Lin can speak with modern framing, or null.
 *
 * This is a correction to an obvious-looking mistake. When a server rejects a version and
 * lists what it does support, the tempting move is to take the first entry and retry - but
 * the list can perfectly well contain only handshake-era versions, and `2025-11-25` in a
 * modern request's `_meta` is not a thing: that version *is* the handshake. Retrying modern
 * against it fails forever.
 *
 * Dates sort as strings, so "is this one modern" is a string comparison.
 */
fun bestModernVersion(supported: List<String>): String? =
  supported.filter { it >= MODERN_VERSION }.maxOrNull()

/**
 * The error codes the specification reserves for itself.
 *
 * -32022 unsupported protocol version, -32021 missing required client capability, -32020
 * header mismatch. A plain -32600 or -32601 from a legacy server is not one of these, which
 * is what makes the test meaningful.
 */
private val MODERN_ERROR_CODES = setOf(-32020, -32021, -32022)

private const val SENTINEL_OPEN = "=?base64?"
private const val SENTINEL_CLOSE = "?="

/**
 * Base64, written out rather than taken from `java.util` or `android.util`.
 *
 * `android.util.Base64` is not on a unit test's classpath and `java.util.Base64` is a
 * desugaring question on older Android. Twenty lines is cheaper than either, and this is
 * exercised by the tests above it.
 */
private fun base64(bytes: ByteArray): String {
  val out = StringBuilder()
  var index = 0
  while (index + 2 < bytes.size) {
    val chunk =
      ((bytes[index].toInt() and 0xFF) shl 16) or
        ((bytes[index + 1].toInt() and 0xFF) shl 8) or
        (bytes[index + 2].toInt() and 0xFF)
    out.append(ALPHABET[(chunk shr 18) and 0x3F])
    out.append(ALPHABET[(chunk shr 12) and 0x3F])
    out.append(ALPHABET[(chunk shr 6) and 0x3F])
    out.append(ALPHABET[chunk and 0x3F])
    index += 3
  }
  when (bytes.size - index) {
    1 -> {
      val chunk = (bytes[index].toInt() and 0xFF) shl 16
      out.append(ALPHABET[(chunk shr 18) and 0x3F])
      out.append(ALPHABET[(chunk shr 12) and 0x3F])
      out.append("==")
    }
    2 -> {
      val chunk =
        ((bytes[index].toInt() and 0xFF) shl 16) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
      out.append(ALPHABET[(chunk shr 18) and 0x3F])
      out.append(ALPHABET[(chunk shr 12) and 0x3F])
      out.append(ALPHABET[(chunk shr 6) and 0x3F])
      out.append('=')
    }
  }
  return out.toString()
}

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
