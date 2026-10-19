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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpWireTest {

  // ---- the request body ----

  @Test
  fun `a modern request carries the protocol metadata in the params`() {
    val request = modernRequest(1, "tools/list")
    val meta = JSONObject(request.body).getJSONObject("params").getJSONObject("_meta")
    assertEquals(MODERN_VERSION, meta.getString("io.modelcontextprotocol/protocolVersion"))
    assertEquals(CLIENT_NAME, meta.getJSONObject("io.modelcontextprotocol/clientInfo").getString("name"))
    assertTrue(meta.has("io.modelcontextprotocol/clientCapabilities"))
  }

  @Test
  fun `the header and the body agree about the version`() {
    // A server must reject a request whose header disagrees with _meta, the point being that
    // a load balancer routing on the header and a server executing on the body cannot be made
    // to disagree. Built from one value here so they cannot drift.
    val request = modernRequest(1, "tools/list")
    val inBody =
      JSONObject(request.body)
        .getJSONObject("params")
        .getJSONObject("_meta")
        .getString("io.modelcontextprotocol/protocolVersion")
    assertEquals(inBody, request.headers["MCP-Protocol-Version"])
  }

  @Test
  fun `the method is mirrored into a header`() {
    assertEquals("tools/list", modernRequest(1, "tools/list").headers["Mcp-Method"])
  }

  @Test
  fun `a call mirrors the tool name and a list does not`() {
    val call = modernRequest(2, "tools/call", JSONObject().put("name", "get_weather"))
    assertEquals("get_weather", call.headers["Mcp-Name"])
    assertFalse(modernRequest(1, "tools/list").headers.containsKey("Mcp-Name"))
  }

  @Test
  fun `a resource read mirrors the uri`() {
    val read = modernRequest(3, "resources/read", JSONObject().put("uri", "file:///a.txt"))
    assertEquals("file:///a.txt", read.headers["Mcp-Name"])
  }

  @Test
  fun `the caller's params survive alongside the metadata`() {
    val call =
      modernRequest(
        4,
        "tools/call",
        JSONObject().put("name", "x").put("arguments", JSONObject().put("q", "hello")),
      )
    val params = JSONObject(call.body).getJSONObject("params")
    assertEquals("hello", params.getJSONObject("arguments").getString("q"))
    assertEquals("x", params.getString("name"))
  }

  @Test
  fun `a legacy request has no metadata`() {
    val request = legacyRequest(1, "tools/list")
    assertFalse(JSONObject(request.body).getJSONObject("params").has("_meta"))
    assertEquals(LEGACY_VERSION, request.headers["MCP-Protocol-Version"])
  }

  @Test
  fun `a notification has no id at all`() {
    // Not a request with the id removed afterwards: a JSON-RPC message carrying an `id` of
    // any value is a request, and a server that answers one the client was not expecting
    // leaves it waiting for a response that never comes.
    val body = JSONObject(legacyNotification("notifications/initialized").body)
    assertFalse(body.has("id"))
    assertEquals("notifications/initialized", body.getString("method"))
  }

  @Test
  fun `initialize says who is asking and what it speaks`() {
    val params = initializeParams()
    assertEquals(LEGACY_VERSION, params.getString("protocolVersion"))
    assertEquals(CLIENT_NAME, params.getJSONObject("clientInfo").getString("name"))
    assertTrue(params.has("capabilities"))
  }

  // ---- header value encoding ----

  @Test
  fun `a plain name goes in a header as it is`() {
    assertEquals("get_weather", encodeHeaderValue("get_weather"))
    assertEquals("admin.tools.list", encodeHeaderValue("admin.tools.list"))
  }

  @Test
  fun `a name outside ascii is carried base64`() {
    assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", encodeHeaderValue("Hello, 世界"))
  }

  @Test
  fun `a padded value is encoded, because the padding would be lost`() {
    assertEquals("=?base64?IHBhZGRlZCA=?=", encodeHeaderValue(" padded "))
  }

  @Test
  fun `a newline is never put in a header`() {
    val encoded = encodeHeaderValue("line1\nline2")
    assertFalse(encoded.contains("\n"))
    assertEquals("=?base64?bGluZTEKbGluZTI=?=", encoded)
  }

  @Test
  fun `a value that looks like the sentinel is encoded anyway`() {
    // Otherwise the server decodes something that was never encoded.
    assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", encodeHeaderValue("=?base64?literal?="))
  }

  @Test
  fun `base64 is right at all three padding lengths`() {
    // Written out by hand rather than taken from java.util, so the remainder cases are where
    // a mistake would live: no bytes over, one over, two over.
    assertEquals("=?base64?w6lhYmM=?=", encodeHeaderValue("\u00e9abc"))
    assertEquals("=?base64?w6k=?=", encodeHeaderValue("\u00e9"))
    assertEquals("=?base64?w6lh?=", encodeHeaderValue("\u00e9a"))
    assertEquals("=?base64?w6lhYg==?=", encodeHeaderValue("\u00e9ab"))
  }

  // ---- reading the response ----

  @Test
  fun `a plain json response is read`() {
    val envelope = responseFrom("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")
    assertNotNull(envelope)
    assertEquals(1, envelope!!.getInt("id"))
  }

  @Test
  fun `an sse stream gives up its final response`() {
    val stream =
      """
      event: message
      data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}

      event: message
      data: {"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"done"}]}}

      """
        .trimIndent()
    val envelope = responseFrom(stream)
    assertNotNull(envelope)
    assertEquals(7, envelope!!.getInt("id"))
  }

  @Test
  fun `keep-alive comments are not mistaken for data`() {
    // Servers are encouraged to send a bare colon line to stop intermediaries closing a quiet
    // stream. Reading one as a message would fail the whole call on a slow server.
    val stream =
      ":\n" +
        ":\n" +
        "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\n" +
        "\n"
    assertEquals(2, responseFrom(stream)?.getInt("id"))
  }

  @Test
  fun `a multi-line data field is joined`() {
    val stream = "data: {\"jsonrpc\":\"2.0\",\"id\":3,\n" + "data: \"result\":{\"ok\":true}}\n\n"
    assertEquals(3, responseFrom(stream)?.getInt("id"))
  }

  @Test
  fun `carriage returns do not break the stream`() {
    val stream = "data: {\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}\r\n\r\n"
    assertEquals(4, responseFrom(stream)?.getInt("id"))
  }

  @Test
  fun `an error on the stream is still the response`() {
    val stream = "data: {\"jsonrpc\":\"2.0\",\"id\":5,\"error\":{\"code\":-32601,\"message\":\"nope\"}}\n\n"
    assertEquals(-32601, responseFrom(stream)?.let { errorIn(it) }?.code)
  }

  @Test
  fun `nothing readable is null rather than an empty answer`() {
    assertNull(responseFrom(""))
    assertNull(responseFrom("   "))
    assertNull(responseFrom("event: ping\n\n"))
    assertNull(responseFrom("not json at all"))
  }

  @Test
  fun `a result envelope has no error`() {
    assertNull(errorIn(JSONObject("""{"jsonrpc":"2.0","id":1,"result":{}}""")))
  }

  @Test
  fun `the versions a server does support are read out of the error`() {
    val envelope =
      JSONObject(
        """
        {"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"Unsupported protocol version",
        "data":{"supported":["2026-07-28","2025-11-25"],"requested":"1900-01-01"}}}
        """
          .trimIndent()
      )
    val error = errorIn(envelope)!!
    assertEquals(-32022, error.code)
    assertEquals(listOf("2026-07-28", "2025-11-25"), error.supported)
  }

  @Test
  fun `an error with no message still says something`() {
    val error = errorIn(JSONObject("""{"jsonrpc":"2.0","id":1,"error":{"code":-1}}"""))!!
    assertTrue(error.message.isNotBlank())
  }

  // ---- which era the server speaks ----

  @Test
  fun `a reserved error code means the server is modern`() {
    for (code in listOf(-32020, -32021, -32022)) {
      assertTrue(
        "code $code should read as modern",
        looksLikeModernError("""{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"x"}}"""),
      )
    }
  }

  @Test
  fun `an ordinary json-rpc error does not mean the server is modern`() {
    // The decision this function exists for. A legacy server answering a modern request
    // returns a plain "invalid request" or "method not found", and reading that as modern
    // would have Lin retry forever instead of sending initialize.
    assertFalse(looksLikeModernError("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid Request"}}"""))
    assertFalse(looksLikeModernError("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""))
  }

  @Test
  fun `a list of only handshake-era versions offers nothing modern`() {
    // The mistake this guards. Taking the first entry of the supported list and retrying
    // modern against "2025-11-25" fails forever, because that version *is* the handshake.
    assertNull(bestModernVersion(listOf("2025-11-25", "2025-06-18")))
    assertNull(bestModernVersion(emptyList()))
  }

  @Test
  fun `the newest modern version in the list is chosen`() {
    assertEquals("2026-07-28", bestModernVersion(listOf("2025-11-25", "2026-07-28")))
    assertEquals("2027-01-01", bestModernVersion(listOf("2026-07-28", "2027-01-01", "2025-06-18")))
  }

  @Test
  fun `an empty or unreadable body is not a modern error`() {
    assertFalse(looksLikeModernError(""))
    assertFalse(looksLikeModernError("Bad Request"))
    assertFalse(looksLikeModernError("<html>400</html>"))
  }

  @Test
  fun `a successful result is not an error of any era`() {
    assertFalse(looksLikeModernError("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
  }
}
