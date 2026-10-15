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
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolsTest {

  private fun listResult(tools: String) = JSONObject("""{"resultType":"complete","tools":[$tools]}""")

  private val weather =
    """
    {"name":"get_weather","title":"Weather Information Provider",
     "description":"Get current weather information for a location",
     "inputSchema":{"type":"object","properties":{"location":{"type":"string",
       "description":"City name or zip code"}},"required":["location"]}}
    """
      .trimIndent()

  @Test
  fun `a tool is read out of the list`() {
    val tools = toolsFrom(listResult(weather))
    assertEquals(1, tools.size)
    assertEquals("get_weather", tools.first().name)
    assertEquals("Get current weather information for a location", tools.first().description)
    assertTrue(tools.first().inputSchema.contains("location"))
  }

  @Test
  fun `the schema is kept as the server wrote it`() {
    // Not flattened into Lin's own parameter list and rebuilt: that would lose nesting,
    // enums and defaults the server meant the model to see.
    val schema = JSONObject(toolsFrom(listResult(weather)).first().inputSchema)
    assertEquals("object", schema.getString("type"))
    assertEquals("City name or zip code", schema.getJSONObject("properties").getJSONObject("location").getString("description"))
    assertEquals("location", schema.getJSONArray("required").getString(0))
  }

  @Test
  fun `a tool with no description falls back to its title`() {
    val tools = toolsFrom(listResult("""{"name":"x","title":"Do the thing","inputSchema":{"type":"object"}}"""))
    assertEquals("Do the thing", tools.first().description)
  }

  @Test
  fun `a tool with no schema is skipped rather than guessed at`() {
    // inputSchema is required and must be an object. Guessing an empty one would have the
    // model call it with no arguments and get back an error it cannot act on.
    assertTrue(toolsFrom(listResult("""{"name":"x","description":"y"}""")).isEmpty())
  }

  @Test
  fun `a nameless tool is skipped`() {
    assertTrue(toolsFrom(listResult("""{"description":"y","inputSchema":{"type":"object"}}""")).isEmpty())
  }

  @Test
  fun `an empty list is an empty list`() {
    assertTrue(toolsFrom(JSONObject("""{"tools":[]}""")).isEmpty())
    assertTrue(toolsFrom(JSONObject("{}")).isEmpty())
  }

  @Test
  fun `a tool with no parameters is still a tool`() {
    val tools = toolsFrom(listResult("""{"name":"now","description":"The time","inputSchema":{"type":"object","additionalProperties":false}}"""))
    assertEquals(1, tools.size)
  }

  // ---- x-mcp-header validity ----

  @Test
  fun `a valid header annotation is accepted`() {
    val schema =
      JSONObject("""{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}""")
    assertTrue(headerAnnotationsAreValid(schema))
  }

  @Test
  fun `a header name with a newline is rejected`() {
    // Not merely invalid: a header name carrying a newline is request splitting.
    val schema =
      JSONObject("""{"type":"object","properties":{"r":{"type":"string","x-mcp-header":"Bad\nName"}}}""")
    assertFalse(headerAnnotationsAreValid(schema))
  }

  @Test
  fun `an empty header name is rejected`() {
    assertFalse(
      headerAnnotationsAreValid(
        JSONObject("""{"type":"object","properties":{"r":{"type":"string","x-mcp-header":""}}}""")
      )
    )
  }

  @Test
  fun `two parameters claiming the same header are rejected`() {
    val schema =
      JSONObject(
        """{"type":"object","properties":{
             "a":{"type":"string","x-mcp-header":"Region"},
             "b":{"type":"string","x-mcp-header":"region"}}}"""
      )
    assertFalse(headerAnnotationsAreValid(schema))
  }

  @Test
  fun `a number parameter may not be a header`() {
    // Excluded by the specification because it cannot round-trip through a header and still
    // compare equal to the body.
    assertFalse(
      headerAnnotationsAreValid(
        JSONObject("""{"type":"object","properties":{"n":{"type":"number","x-mcp-header":"N"}}}""")
      )
    )
    assertTrue(
      headerAnnotationsAreValid(
        JSONObject("""{"type":"object","properties":{"n":{"type":"integer","x-mcp-header":"N"}}}""")
      )
    )
  }

  @Test
  fun `one malformed tool does not cost the user the others`() {
    // The specification is explicit about this: exclude the tool, keep the list.
    val tools =
      toolsFrom(
        listResult(
          """{"name":"bad","description":"d","inputSchema":{"type":"object","properties":{"r":{"type":"string","x-mcp-header":""}}}},""" +
            weather
        )
      )
    assertEquals(listOf("get_weather"), tools.map { it.name })
  }

  @Test
  fun `a schema with no properties needs no checking`() {
    assertTrue(headerAnnotationsAreValid(JSONObject("""{"type":"object"}""")))
  }

  // ---- naming ----

  @Test
  fun `a tool is prefixed with the server it came from`() {
    assertEquals("github.search", qualifiedName("GitHub", "search"))
  }

  @Test
  fun `two servers offering the same tool do not collide`() {
    // The collision the specification itself gives as the example.
    assertFalse(qualifiedName("github", "search") == qualifiedName("notion", "search"))
  }

  @Test
  fun `a server name with awkward characters still makes a usable prefix`() {
    assertEquals("my-server.search", qualifiedName("My Server", "search"))
    assertEquals("a-b.x", qualifiedName("a/b", "x"))
  }

  @Test
  fun `a server with no usable name leaves the tool unprefixed`() {
    assertEquals("search", qualifiedName("", "search"))
    assertEquals("search", qualifiedName("///", "search"))
  }

  // ---- results ----

  @Test
  fun `text blocks are the answer`() {
    val outcome =
      outcomeFrom(JSONObject("""{"content":[{"type":"text","text":"72F, partly cloudy"}],"isError":false}"""))
    assertEquals("72F, partly cloudy", outcome.text)
    assertFalse(outcome.failed)
  }

  @Test
  fun `several text blocks are joined`() {
    val outcome =
      outcomeFrom(JSONObject("""{"content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}"""))
    assertEquals("one\ntwo", outcome.text)
  }

  @Test
  fun `an execution error is reported as one`() {
    val outcome =
      outcomeFrom(
        JSONObject("""{"content":[{"type":"text","text":"Invalid departure date"}],"isError":true}""")
      )
    assertTrue(outcome.failed)
    assertEquals("Invalid departure date", outcome.text)
  }

  @Test
  fun `an image is named rather than pasted into the context`() {
    // The bytes are base64 in the JSON. A few hundred kilobytes of it would end the
    // conversation on a 2B model.
    val outcome =
      outcomeFrom(JSONObject("""{"content":[{"type":"image","data":"AAAA","mimeType":"image/png"}]}"""))
    assertFalse(outcome.text.contains("AAAA"))
    assertTrue(outcome.text.contains("image"))
  }

  @Test
  fun `structured content is used only when there is no text`() {
    // A server returning both is required to put the same data in the text block, so sending
    // both would pay for one answer twice.
    val both =
      outcomeFrom(
        JSONObject("""{"content":[{"type":"text","text":"22.5C"}],"structuredContent":{"temperature":22.5}}""")
      )
    assertEquals("22.5C", both.text)

    val only = outcomeFrom(JSONObject("""{"structuredContent":{"temperature":22.5}}"""))
    assertTrue(only.text.contains("22.5"))
  }

  @Test
  fun `an embedded resource contributes its text`() {
    val outcome =
      outcomeFrom(
        JSONObject("""{"content":[{"type":"resource","resource":{"uri":"file:///a","text":"hello"}}]}""")
      )
    assertEquals("hello", outcome.text)
  }

  @Test
  fun `a resource link is named`() {
    val outcome =
      outcomeFrom(JSONObject("""{"content":[{"type":"resource_link","uri":"file:///a","name":"main.rs"}]}"""))
    assertTrue(outcome.text.contains("main.rs"))
  }

  @Test
  fun `a result with nothing in it still says something`() {
    assertTrue(outcomeFrom(JSONObject("{}")).text.isNotBlank())
    assertTrue(outcomeFrom(JSONObject("""{"isError":true}""")).text.isNotBlank())
    assertTrue(outcomeFrom(JSONObject("""{"content":[]}""")).text.isNotBlank())
  }

  @Test
  fun `a block of a kind nobody has heard of is skipped, not fatal`() {
    val outcome =
      outcomeFrom(JSONObject("""{"content":[{"type":"hologram"},{"type":"text","text":"ok"}]}"""))
    assertEquals("ok", outcome.text)
  }
}
