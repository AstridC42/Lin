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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPromptTest {

  private fun tool(name: String, guidance: String = "") =
    object : AgentTool {
      override val name = name
      override val description = "does $name"
      override val parameters = emptyList<ToolParameter>()
      override val roles = AgentRole.entries.toSet()
      override val guidance = guidance

      override suspend fun run(arguments: Map<String, Any?>) = ToolResult.Ok("")
    }

  @Test
  fun `the guidance of every tool on offer is in the prompt`() {
    val prompt =
      capabilityPrompt(
        listOf(tool("weather", "Use weather for what it is doing outside."), tool("clock", "Use clock for the time."))
      )
    assertTrue(prompt, prompt.contains("what it is doing outside"))
    assertTrue(prompt, prompt.contains("Use clock for the time."))
  }

  @Test
  fun `a tool that is not on offer is not described`() {
    // The whole point. The prompt this replaced was a constant that still claimed Lin could
    // not change Wi-Fi after Shizuku had made that untrue, and said nothing about six tools
    // added after it was written.
    val prompt = capabilityPrompt(listOf(tool("weather", "Use weather for the forecast.")))
    assertFalse(prompt, prompt.contains("clock"))
  }

  @Test
  fun `a tool whose description is enough adds nothing`() {
    val prompt = capabilityPrompt(listOf(tool("clock"), tool("weather", "Use weather.")))
    assertTrue(prompt, prompt.contains("Use weather."))
    // No empty gap where the silent tool would have been.
    assertFalse(prompt, prompt.contains("  "))
  }

  @Test
  fun `the same tools always give byte-identical text`() {
    // An unchanged prefix is what lets a server reuse its cache of it. A prompt that
    // reshuffled itself would throw that away on every question.
    val tools = listOf(tool("b", "Second."), tool("a", "First."), tool("c", "Third."))
    assertEquals(capabilityPrompt(tools), capabilityPrompt(tools.shuffled()))
  }

  @Test
  fun `the order is the registry's order, not the order they were passed`() {
    val prompt = capabilityPrompt(listOf(tool("zebra", "Zebra."), tool("apple", "Apple.")))
    assertTrue(prompt, prompt.indexOf("Apple.") < prompt.indexOf("Zebra."))
  }

  @Test
  fun `the closing rule is always there`() {
    // Without it a model offered three tools explains how to do the fourth thing manually,
    // which reads as Lin being able to do it.
    for (tools in listOf(emptyList(), listOf(tool("a", "A.")))) {
      assertTrue(capabilityPrompt(tools).contains("rather than"))
    }
  }

  @Test
  fun `no tools at all still says what to do`() {
    val prompt = capabilityPrompt(emptyList())
    assertTrue(prompt, prompt.contains("no tools"))
    assertTrue(prompt, prompt.isNotBlank())
  }

  @Test
  fun `the prompt does not run sentences together`() {
    val prompt = capabilityPrompt(listOf(tool("a", "First sentence."), tool("b", "Second sentence.")))
    assertTrue(prompt, prompt.contains("First sentence. Second sentence."))
  }
}
