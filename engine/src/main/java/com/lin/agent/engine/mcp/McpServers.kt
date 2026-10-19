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

import org.json.JSONArray
import org.json.JSONObject

/**
 * One MCP server the user has added.
 *
 * [header] is a whole `Name: value` line rather than a token, for the same reason the model
 * server's is: what a server wants in front of it varies - a bearer token, a Cloudflare Access
 * service token, an api-key header - and storing the parts separately would only reassemble
 * them.
 */
data class McpServer(val name: String, val url: String, val header: String = "") {

  /** The header as a pair, or null when there is none or it is malformed. */
  fun authHeader(): Pair<String, String>? {
    val colon = header.indexOf(':')
    if (colon <= 0) return null
    val field = header.take(colon).trim()
    val value = header.substring(colon + 1).trim()
    return if (field.isEmpty() || value.isEmpty()) null else field to value
  }
}

/**
 * The servers as stored, which is one JSON string in the settings.
 *
 * JSON rather than a line-based format because a header value can contain anything, including
 * the separators any simpler format would need. One setting rather than a table because the
 * number of these is small and a database for three rows is ceremony.
 *
 * Anything unreadable reads as no servers rather than throwing: this is on the path of
 * building the tool list for a turn, and a settings value corrupted by a half-finished write
 * must not be able to stop Lin answering.
 */
fun serversFrom(stored: String?): List<McpServer> {
  val text = stored.orEmpty().trim()
  if (text.isEmpty()) return emptyList()
  val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
  val found = mutableListOf<McpServer>()
  for (index in 0 until array.length()) {
    val each = array.optJSONObject(index) ?: continue
    val url = each.optString("url").trim()
    if (url.isEmpty()) continue
    found.add(
      McpServer(
        name = each.optString("name").trim().ifEmpty { hostOf(url) },
        url = url,
        header = each.optString("header").trim(),
      )
    )
  }
  // One entry per URL. Adding the same server twice would offer every one of its tools twice
  // under two prefixes, and the model would pick between them at random.
  return found.distinctBy { it.url }
}

fun storedFrom(servers: List<McpServer>): String {
  val array = JSONArray()
  servers.forEach { server ->
    array.put(
      JSONObject()
        .put("name", server.name)
        .put("url", server.url)
        .put("header", server.header)
    )
  }
  return array.toString()
}

/**
 * A name for a server the user did not name, taken from its address.
 *
 * Used as the prefix on every tool that server offers, so it has to be something - an unnamed
 * server would otherwise have its tools collide with another's.
 */
fun hostOf(url: String): String =
  url
    .substringAfter("://", url)
    .substringBefore('/')
    .substringBefore(':')
    .removePrefix("www.")
    .ifEmpty { "server" }
