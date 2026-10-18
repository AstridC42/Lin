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

package com.lin.agent.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTest {

  @Test
  fun `a tool is described in plain words, not by its name`() {
    assertEquals("Searched the web", stepVerb("search_web", running = false))
    assertEquals("Searching the web", stepVerb("search_web", running = true))
  }

  @Test
  fun `an unknown tool is still readable`() {
    // A tool from an MCP server arrives as "server.tool_name".
    assertEquals("Create issue", stepVerb("github.create_issue", running = false))
  }

  @Test
  fun `a turn is one line, with repeats counted`() {
    val steps = listOf(Step("search_web"), Step("read_page"), Step("read_page"), Step("read_page"))
    assertEquals("Searched the web · Read a page ×3", stepsSummary(steps))
  }

  @Test
  fun `failures are counted in the summary itself`() {
    // A turn that went wrong says so without anybody opening it.
    val steps = listOf(Step("search_web", failed = true), Step("search_web"))
    assertEquals("Searched the web ×2 · 1 could not", stepsSummary(steps))
  }

  @Test
  fun `a running turn says what it is doing now`() {
    val steps = listOf(Step("search_web"), Step("read_page", running = true))
    assertEquals("Reading a page…", stepsSummary(steps))
  }

  @Test
  fun `repeats are only counted when they are next to each other`() {
    val steps = listOf(Step("search_web"), Step("read_page"), Step("search_web"))
    assertEquals("Searched the web · Read a page · Searched the web", stepsSummary(steps))
  }

  @Test
  fun `no steps, no summary`() {
    assertEquals("", stepsSummary(emptyList()))
  }

  // ---- sources ----

  @Test
  fun `the trailing source list is lifted off the answer`() {
    val answer =
      """
      The Orin Nano Super delivers 67 TOPS [1].

      Sources:
      1. https://www.nvidia.com/en-us/jetson/
      2. [TechPowerUp](https://www.techpowerup.com/gpu-specs/x.c4377)
      """.trimIndent()
    val split = splitSources(answer)
    assertEquals("The Orin Nano Super delivers 67 TOPS [1].", split.body)
    assertEquals(listOf("https://www.nvidia.com/en-us/jetson/", "https://www.techpowerup.com/gpu-specs/x.c4377"), split.sources)
  }

  @Test
  fun `a bold or heading-styled sources line counts`() {
    assertEquals(1, splitSources("Answer.\n\n**Sources:**\n- https://a.example").sources.size)
    assertEquals(1, splitSources("Answer.\n\n## Sources\n- https://a.example").sources.size)
  }

  @Test
  fun `the word sources in a sentence is not a list`() {
    val answer = "Several sources disagree about this.\nhttps://a.example"
    assertEquals(splitSources(answer), Sourced(answer, emptyList()))
  }

  @Test
  fun `a sources list with prose in it is left where it is`() {
    // Taking it off would lose the prose - and it has a link in it, so only the prose rule
    // keeps it, not the "no addresses at all" rule.
    val answer = "Answer.\n\nSources:\nOnly one of them was any use:\n- https://a.example"
    assertEquals(Sourced(answer, emptyList()), splitSources(answer))
    assertEquals(Sourced("Answer.\n\nSources:\nI could not find any.", emptyList()), splitSources("Answer.\n\nSources:\nI could not find any."))
  }

  @Test
  fun `the same address twice is one source`() {
    assertEquals(1, splitSources("A.\nSources:\n1. https://a.example\n2. https://a.example").sources.size)
  }

  @Test
  fun `a chip shows the domain`() {
    assertEquals("nvidia.com", domainOf("https://www.nvidia.com/en-us/jetson/"))
    assertEquals("docs.nvidia.com", domainOf("https://docs.nvidia.com/x?y=1"))
    assertEquals("192.168.1.5", domainOf("http://192.168.1.5:8080/search"))
  }

  // ---- greeting ----

  @Test
  fun `the greeting knows the time of day and the name`() {
    assertEquals("Good morning, Sam.", greetingFor(8, "Sam"))
    assertEquals("Good afternoon, Sam.", greetingFor(14, "Sam"))
    assertEquals("Good evening, Sam.", greetingFor(20, "Sam"))
    assertEquals("Hello, Sam.", greetingFor(2, "Sam"))
  }

  @Test
  fun `no name, no comma`() {
    assertEquals("Good evening.", greetingFor(19, "  "))
    assertTrue(!greetingFor(19, "").contains(","))
  }
}
