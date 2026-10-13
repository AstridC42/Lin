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

import java.net.URI

/**
 * Which kind of server is at the other end.
 *
 * Worth being honest about what this enum is and is not. [VLLM] and [UNSLOTH] speak the same
 * protocol - OpenAI's chat completions - and share one backend; they are separate entries
 * because what a person has to type differs, not because the wire format does. vLLM usually has
 * no key and listens on 8000; Unsloth always issues an `sk-unsloth-…` key and gets its port
 * assigned. Presenting one "OpenAI-compatible" option and leaving somebody to work out which
 * fields their server needs would be the tidier enum and the worse product.
 *
 * [OLLAMA] is genuinely different: its own `/api/chat`, its own streaming shape, its own model
 * list. It keeps its own backend.
 */
enum class ServerKind(
  /** What a request path hangs off, and what the model list is read from. */
  internal val dialect: Dialect,
  /** Assumed only when the address has no port, which is where this gets typed wrong. */
  internal val defaultPort: Int,
) {
  OLLAMA(Dialect.OLLAMA, defaultPort = 11434),

  /** `vllm serve <model>`, which listens on 8000 and wants no key by default. */
  VLLM(Dialect.OPEN_AI, defaultPort = 8000),

  /**
   * Unsloth, which serves the OpenAI routes through llama-server behind an issued key.
   *
   * 8080 is llama-server's own default and the best guess available: Unsloth assigns the port,
   * so an address pasted from it carries one and this is never consulted.
   */
  UNSLOTH(Dialect.OPEN_AI, defaultPort = 8080);

  internal enum class Dialect {
    OLLAMA,
    OPEN_AI,
  }

  /** Whether this speaks OpenAI's chat completions, which decides the backend. */
  val speaksOpenAi: Boolean
    get() = dialect == Dialect.OPEN_AI
}

/**
 * The kind a stored setting names, defaulting to [ServerKind.OLLAMA].
 *
 * Ollama by default because it is what this app shipped with: somebody upgrading has a working
 * server configured and no stored kind, and they should find it still working rather than
 * pointed at a dialect their server does not speak.
 */
fun serverKindNamed(stored: String): ServerKind =
  ServerKind.entries.firstOrNull { it.name.equals(stored.trim(), ignoreCase = true) }
    ?: ServerKind.OLLAMA

/**
 * The URL of one endpoint on [kind], from whatever the user typed, or null if they typed nothing.
 *
 * All of the forgiveness lives here, because this field is typed on a phone keyboard and every
 * one of these mistakes is indistinguishable from the server being down:
 *
 *  - no scheme, which is what typing a LAN address from memory produces
 *  - a trailing slash, which is what a browser's address bar hands over
 *  - the API path already attached, which is what pasting from any of these projects' own
 *    documentation gives - `http://localhost:8000/v1` is exactly what vLLM's README shows
 *  - no port on a plain-http address, which almost always means the default
 *
 * The scheme default is `http`, not `https`: an address with no scheme is nearly always a LAN
 * address or an IP, and none of these serve TLS by themselves. The port default is applied only
 * under `http` - under `https` something is terminating TLS, and that listens on 443.
 */
internal fun serverUrl(typed: String, kind: ServerKind, endpoint: Endpoint): String? {
  var text = typed.trim()
  if (text.isEmpty()) return null
  if (!text.contains("://")) text = "http://$text"
  text = text.trimEnd('/')

  // Any known suffix, not just this call's: one address field is shared between the model list
  // and the conversation, so a pasted chat URL must not make the model list ask for
  // `/v1/chat/completions/models`.
  for (suffix in PASTED_SUFFIXES) {
    if (text.endsWith(suffix, ignoreCase = true)) {
      text = text.dropLast(suffix.length).trimEnd('/')
      break
    }
  }

  val uri = runCatching { URI(text) }.getOrNull() ?: return null
  val host = uri.host ?: return null
  val base =
    if (uri.port == -1 && uri.scheme.equals("http", ignoreCase = true)) {
      "${uri.scheme}://$host:${kind.defaultPort}${uri.path.orEmpty()}"
    } else {
      text
    }

  val path =
    when (kind.dialect) {
      ServerKind.Dialect.OLLAMA ->
        when (endpoint) {
          Endpoint.MODELS -> "/api/tags"
          Endpoint.CHAT -> "/api/chat"
        }
      ServerKind.Dialect.OPEN_AI ->
        when (endpoint) {
          Endpoint.MODELS -> "/v1/models"
          Endpoint.CHAT -> "/v1/chat/completions"
        }
    }
  return "$base$path"
}

/** The two things this app ever asks a server for. */
internal enum class Endpoint {
  MODELS,
  CHAT,
}

/**
 * Endpoint paths a person plausibly pastes, longest first.
 *
 * Longest first matters: `/v1/chat/completions` has to be tried before `/v1`, or stripping would
 * leave `/chat/completions` on the end of the address.
 */
private val PASTED_SUFFIXES =
  listOf(
    "/v1/chat/completions",
    "/v1/completions",
    "/v1/models",
    "/api/chat",
    "/api/tags",
    "/api",
    "/v1",
  )
