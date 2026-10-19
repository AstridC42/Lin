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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServersTest {

  @Test
  fun `a stored server reads back whole`() {
    val servers = listOf(McpServer("GitHub", "https://mcp.example.com/mcp", "Authorization: Bearer abc"))
    assertEquals(servers, serversFrom(storedFrom(servers)))
  }

  @Test
  fun `nothing stored is no servers`() {
    assertTrue(serversFrom(null).isEmpty())
    assertTrue(serversFrom("").isEmpty())
    assertTrue(serversFrom("   ").isEmpty())
  }

  @Test
  fun `an unreadable value is no servers rather than a crash`() {
    // This is read while building the tool list for a turn. A value corrupted by a
    // half-finished write must not be able to stop Lin answering.
    assertTrue(serversFrom("{not json").isEmpty())
    assertTrue(serversFrom("{\"url\":\"x\"}").isEmpty())
  }

  @Test
  fun `an entry with no url is skipped`() {
    assertTrue(serversFrom("""[{"name":"x"}]""").isEmpty())
  }

  @Test
  fun `an unnamed server is named after its host`() {
    val servers = serversFrom("""[{"url":"https://mcp.example.com/mcp"}]""")
    assertEquals("mcp.example.com", servers.first().name)
  }

  @Test
  fun `the same server twice is one server`() {
    // Added twice, every one of its tools would be offered twice under two prefixes and the
    // model would pick between them at random.
    val stored = """[{"name":"a","url":"https://x/mcp"},{"name":"b","url":"https://x/mcp"}]"""
    assertEquals(1, serversFrom(stored).size)
  }

  @Test
  fun `a header splits into a field and a value`() {
    val pair = McpServer("x", "https://x", "Authorization: Bearer abc").authHeader()
    assertEquals("Authorization" to "Bearer abc", pair)
  }

  @Test
  fun `a value containing a colon survives`() {
    // A bearer token or a URL in a header value both contain colons; splitting on the last
    // one, or on all of them, would truncate it.
    val pair = McpServer("x", "https://x", "X-Where: https://host:8443/path").authHeader()
    assertEquals("X-Where" to "https://host:8443/path", pair)
  }

  @Test
  fun `a malformed header is no header rather than a bad one`() {
    assertNull(McpServer("x", "https://x", "").authHeader())
    assertNull(McpServer("x", "https://x", "nocolon").authHeader())
    assertNull(McpServer("x", "https://x", ": value").authHeader())
    assertNull(McpServer("x", "https://x", "Name:").authHeader())
  }

  @Test
  fun `a host is pulled out of any shape of url`() {
    assertEquals("mcp.example.com", hostOf("https://mcp.example.com/mcp"))
    assertEquals("mcp.example.com", hostOf("http://mcp.example.com:8080/mcp"))
    assertEquals("example.com", hostOf("https://www.example.com/mcp"))
    assertEquals("192.168.1.50", hostOf("http://192.168.1.50:3000/mcp"))
    assertEquals("server", hostOf(""))
  }

  @Test
  fun `several servers keep their order`() {
    val servers =
      listOf(
        McpServer("one", "https://a/mcp"),
        McpServer("two", "https://b/mcp"),
        McpServer("three", "https://c/mcp"),
      )
    assertEquals(servers.map { it.name }, serversFrom(storedFrom(servers)).map { it.name })
  }

  @Test
  fun `a header with no value stored is empty rather than null text`() {
    val back = serversFrom(storedFrom(listOf(McpServer("a", "https://a/mcp"))))
    assertEquals("", back.first().header)
  }
}
