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

/** One tool a server offers. [inputSchema] is passed to the model as the server wrote it. */
data class RemoteTool(
  val name: String,
  val description: String,
  val inputSchema: String,
  val title: String = "",
)

/**
 * The tools in a `tools/list` result.
 *
 * Two rules worth stating, both from the specification and both the kind that are easy to
 * leave out and hard to notice:
 *
 *  - A tool whose `x-mcp-header` annotation is invalid **must** be excluded rather than
 *    rejected along with the whole list, so that one malformed definition does not cost the
 *    user every other tool on that server. See [headerAnnotationsAreValid].
 *  - A tool with no `inputSchema` is not a tool with no parameters: the field is required and
 *    must be an object. Something without one is malformed, and guessing an empty schema for
 *    it would have the model call it with nothing and get an error it cannot act on.
 */
fun toolsFrom(result: JSONObject): List<RemoteTool> {
  val array = result.optJSONArray("tools") ?: return emptyList()
  val found = mutableListOf<RemoteTool>()
  for (index in 0 until array.length()) {
    val each = array.optJSONObject(index) ?: continue
    val name = each.optString("name").trim()
    if (name.isEmpty()) continue
    val schema = each.optJSONObject("inputSchema") ?: continue
    if (!headerAnnotationsAreValid(schema)) continue
    found.add(
      RemoteTool(
        name = name,
        description = each.optString("description").ifEmpty { each.optString("title") },
        inputSchema = schema.toString(),
        title = each.optString("title"),
      )
    )
  }
  return found
}

/**
 * Whether every `x-mcp-header` in this schema is one a client may honour.
 *
 * A client on the HTTP transport must mirror annotated parameters into `Mcp-Param-*` headers
 * and the server validates that they match the body - so an annotation Lin cannot represent
 * is not a cosmetic problem: every call to that tool would be rejected. Excluding the tool is
 * what the specification asks for, and it is better than offering the model something that
 * cannot work.
 *
 * Checked at the top level only. The rules require an annotated property to be reachable
 * through `properties` alone, and a nested object's annotations are the uncommon case; what
 * matters here is catching the names that are outright illegal.
 */
fun headerAnnotationsAreValid(schema: JSONObject): Boolean {
  val properties = schema.optJSONObject("properties") ?: return true
  val seen = mutableSetOf<String>()
  for (key in properties.keys()) {
    val property = properties.optJSONObject(key) ?: continue
    if (!property.has("x-mcp-header")) continue
    val header = property.optString("x-mcp-header")
    if (header.isEmpty()) return false
    // HTTP token characters only, and no control characters: a header name with a newline in
    // it is a request-splitting bug waiting to happen, not merely an invalid tool.
    if (!header.all { it.isLetterOrDigit() || it in TOKEN_PUNCTUATION }) return false
    if (!seen.add(header.lowercase())) return false
    // number is excluded by the specification because it cannot round-trip through a header
    // and compare equal to the body.
    if (property.optString("type") !in HEADERABLE_TYPES) return false
  }
  return true
}

/**
 * What a tool is called once it is in Lin's list.
 *
 * Prefixed with the server, because tool names are only unique within one server and two
 * servers each offering `search` is the example the specification itself gives. Without this
 * the second one to load would be dropped and nobody would know which.
 *
 * The separator is a dot because it is in the allowed character set for a tool name and a
 * model handles `github.search` better than it handles a name with a slash or a colon in it.
 */
fun qualifiedName(serverName: String, toolName: String): String {
  val prefix = serverName.lowercase().map { if (it.isLetterOrDigit() || it == '_') it else '-' }
    .joinToString("")
    .trim('-')
  return if (prefix.isEmpty()) toolName else "$prefix.$toolName"
}

/** What came back from a `tools/call`. */
data class McpOutcome(val text: String, val failed: Boolean)

/**
 * A tool result flattened to the text a model can read.
 *
 * MCP content is a list of blocks of several kinds and Lin's tool results are a string, so
 * something has to be decided here:
 *
 *  - Text blocks are the answer and are joined.
 *  - An image or audio block is named rather than included. The bytes are base64 in the JSON,
 *    and putting a few hundred kilobytes of it into a 2B model's context would end the
 *    conversation; saying one is there lets the model mention it.
 *  - `structuredContent` is used **only** when there is no text, because a server that
 *    returns both is required to put the same data in the text block, and sending both would
 *    pay for the same answer twice.
 *
 * `isError` is a tool execution error - the server ran it and it did not work - which the
 * model should see so it can correct itself, rather than a protocol fault.
 */
fun outcomeFrom(result: JSONObject): McpOutcome {
  val failed = result.optBoolean("isError", false)
  val content = result.optJSONArray("content")
  val parts = mutableListOf<String>()
  if (content != null) {
    for (index in 0 until content.length()) {
      val block = content.optJSONObject(index) ?: continue
      when (block.optString("type")) {
        "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let(parts::add)
        "image" -> parts.add("[an image the server returned, which Lin cannot show you here]")
        "audio" -> parts.add("[a sound the server returned]")
        "resource_link" ->
          parts.add(
            "[a link to ${block.optString("name").ifEmpty { block.optString("uri") }}]"
          )
        "resource" ->
          block.optJSONObject("resource")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let(parts::add)
        else -> Unit
      }
    }
  }
  if (parts.isEmpty()) {
    val structured = result.opt("structuredContent")
    if (structured != null && structured != JSONObject.NULL) parts.add(structured.toString())
  }
  val text =
    parts.joinToString("\n").ifBlank {
      if (failed) "The server reported an error but did not say what."
      else "The server ran it and returned nothing."
    }
  return McpOutcome(text, failed)
}

private const val TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"
private val HEADERABLE_TYPES = setOf("string", "integer", "boolean")
