/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt has to fit in a budget it shares with everything else.
 *
 * The models this app runs give it about 4,000 tokens in total, and the system prompt is only one
 * claim on that: roughly 1,200 more go on tool descriptions, several hundred on the skill list, and
 * whatever is left has to hold the conversation, the tool results and the reply.
 *
 * The prompt reached ~1,800 tokens by having a section added for every capability, none ever
 * measured. Together with the tools that left almost nothing, and the first tool call returning a
 * list pushed the session over - which is what the reported "problem with the default max tokens"
 * and the crash on any phone action were.
 *
 * These bounds are deliberately generous - they are not a style rule, they are the line past which
 * the app stops working - but they fail before a device does.
 */
class SystemPromptBudgetTest {

  /** Four characters per token is the usual rough English estimate, and near enough here. */
  private fun approxTokens(prompt: String): Int = prompt.length / 4

  private fun prompt(mode: AssistantMode, everything: Boolean) =
    AssistantPrompts.resolve(
      currentPrompt = "",
      mode = mode,
      hasMcpTools = everything,
      webSearchEnabled = everything,
      webSearchInstance = if (everything) "searx.example.org" else "",
      smartHomeEnabled = everything,
      hasMemory = everything,
    )

  @Test
  fun everyModeFitsTheBudget() {
    for (mode in AssistantMode.values()) {
      val tokens = approxTokens(prompt(mode, everything = false))
      assertTrue(
        "${mode.id} system prompt is ~$tokens tokens, over the $BUDGET budget",
        tokens <= BUDGET,
      )
    }
  }

  /** Every capability switched on at once is the worst case, and it is the one that broke. */
  @Test
  fun theWorstCaseFitsTheBudget() {
    val tokens = approxTokens(prompt(AssistantMode.AUTO, everything = true))
    assertTrue(
      "system prompt with everything enabled is ~$tokens tokens, over the $WORST_CASE_BUDGET budget",
      tokens <= WORST_CASE_BUDGET,
    )
  }

  /** The routing procedure is what makes skills get used; a trim must not take it out. */
  @Test
  fun trimmingHasNotRemovedTheRouting() {
    val text = prompt(AssistantMode.AUTO, everything = false)
    assertTrue("the routing procedure must survive", text.contains("EVERY request"))
    assertTrue("load_skill must be named", text.contains("load_skill"))
    assertTrue("the skills list must be present", text.contains(AssistantPrompts.SKILLS_PLACEHOLDER))
  }

  /** A capability that is off should be absent, not described and then disclaimed. */
  @Test
  fun disabledCapabilitiesCostNothing() {
    val off = prompt(AssistantMode.AUTO, everything = false)
    assertTrue("web search should be absent when off", !off.contains("webSearch("))
    assertTrue("smart home should be absent when off", !off.contains("listSmartHomeDevices"))
    assertTrue(
      "the MCP placeholder should be absent when off",
      !off.contains(AssistantPrompts.TOOLS_PLACEHOLDER),
    )
  }

  @Test
  fun anEmptyMemoryCostsNothing() {
    val off = prompt(AssistantMode.AUTO, everything = false)
    assertTrue(
      "the memory section should be absent when there is nothing remembered",
      !off.contains(AssistantPrompts.MEMORY_PLACEHOLDER),
    )
  }

  /**
   * What is remembered goes in before the routing, because it changes what the right answer is
   * rather than how to go looking for it.
   */
  @Test
  fun whatIsRememberedComesBeforeTheRouting() {
    val text = prompt(AssistantMode.AUTO, everything = true)
    val memory = text.indexOf(AssistantPrompts.MEMORY_PLACEHOLDER)
    val routing = text.indexOf("For EVERY request")
    assertTrue("the memory section must be present", memory >= 0)
    assertTrue("the routing must be present", routing >= 0)
    assertTrue("what is remembered must come before the routing", memory < routing)
  }

  /**
   * Every placeholder the prompt uses is one somebody has to fill in.
   *
   * There are two places that do the filling - AssistantTask, for the session built at launch, and
   * the assistant screen, for the one rebuilt when the mode, the memory or the MCP selection
   * changes - and they are not the same code. Adding a third placeholder to the prompt and
   * updating only one of them is invisible: the other hands the model the literal text
   * `___WHATEVER___`, which is worse than an absent section and shows up nowhere. That has already
   * happened once, with memory.
   *
   * So this pins the set. A new placeholder fails here, and the failure is the reminder to teach
   * both substituters about it.
   */
  @Test
  fun thePromptUsesOnlyPlaceholdersThatAreSubstituted() {
    val found =
      Regex("___[A-Z_]+___")
        .findAll(prompt(AssistantMode.AUTO, everything = true))
        .map { it.value }
        .toSet()
    assertEquals(
      "a placeholder here must be filled in by both AssistantTask and AssistantScreen",
      setOf(
        AssistantPrompts.SKILLS_PLACEHOLDER,
        AssistantPrompts.TOOLS_PLACEHOLDER,
        AssistantPrompts.MEMORY_PLACEHOLDER,
      ),
      found,
    )
  }

  companion object {
    private const val BUDGET = 800

    /**
     * Every capability on, including a memory section. Raised from 950 when memory was added: the
     * heading is real cost, and the facts themselves are capped separately in MemoryBook rather
     * than measured here, since they are substituted in after this prompt is built.
     */
    private const val WORST_CASE_BUDGET = 1000
  }
}
