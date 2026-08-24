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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptsTest {

  @Test
  fun build_listsWebSearchOnlyWhenItIsAvailable() {
    val withSearch =
      AssistantPrompts.build(
        mode = AssistantMode.AUTO,
        hasMcpTools = false,
        webSearchEnabled = true,
        webSearchInstance = "searx.example.org",
      )
    assertTrue(withSearch.contains("searx.example.org"))
    assertTrue(withSearch.contains("webSearch(query"))

    val withoutSearch =
      AssistantPrompts.build(mode = AssistantMode.AUTO, hasMcpTools = false, webSearchEnabled = false)
    assertTrue(withoutSearch.contains("Web search is not set up"))
    assertFalse(withoutSearch.contains("webSearch(query"))
  }

  @Test
  fun build_includesMcpSectionOnlyWhenToolsAreEnabled() {
    val withMcp =
      AssistantPrompts.build(mode = AssistantMode.AGENT, hasMcpTools = true, webSearchEnabled = false)
    assertTrue(withMcp.contains(AssistantPrompts.TOOLS_PLACEHOLDER))

    val withoutMcp =
      AssistantPrompts.build(
        mode = AssistantMode.AGENT,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    assertFalse(withoutMcp.contains(AssistantPrompts.TOOLS_PLACEHOLDER))
  }

  @Test
  fun build_alwaysLeavesTheSkillsPlaceholderForPromptExpander() {
    for (mode in AssistantMode.entries) {
      val prompt =
        AssistantPrompts.build(mode = mode, hasMcpTools = false, webSearchEnabled = false)
      assertTrue(mode.name, prompt.contains(AssistantPrompts.SKILLS_PLACEHOLDER))
    }
  }

  @Test
  fun isGenerated_recognisesItsOwnOutput() {
    val prompt =
      AssistantPrompts.build(mode = AssistantMode.CHAT, hasMcpTools = false, webSearchEnabled = false)
    assertTrue(AssistantPrompts.isGenerated(prompt))
  }

  @Test
  fun isGenerated_rejectsAnEditedPromptSoItIsNeverOverwritten() {
    val prompt =
      AssistantPrompts.build(mode = AssistantMode.CHAT, hasMcpTools = false, webSearchEnabled = false)
    assertFalse(AssistantPrompts.isGenerated(prompt + "\nAlways answer in French."))
    assertFalse(AssistantPrompts.isGenerated(prompt.replace("Star Storyteller", "Jarvis")))
  }

  @Test
  fun isGenerated_rejectsAHandWrittenPrompt() {
    assertFalse(AssistantPrompts.isGenerated(""))
    assertFalse(AssistantPrompts.isGenerated("You are a helpful assistant."))
    // A forged marker line without a matching fingerprint does not count as generated.
    assertFalse(AssistantPrompts.isGenerated("<!-- star-storyteller-assistant:0 -->\nhello"))
  }

  @Test
  fun resolve_rebuildsAGeneratedPromptForTheNewMode() {
    val chat =
      AssistantPrompts.build(mode = AssistantMode.CHAT, hasMcpTools = false, webSearchEnabled = false)
    val switched =
      AssistantPrompts.resolve(
        currentPrompt = chat,
        mode = AssistantMode.RESEARCH,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    assertTrue(switched.contains("Current mode: WEB."))
    assertFalse(switched.contains("Current mode: CHAT."))
    assertTrue(AssistantPrompts.isGenerated(switched))
  }

  @Test
  fun resolve_keepsACustomPromptAndAppendsTheMode() {
    val custom = "You are Jarvis. Always answer in one sentence."
    val resolved =
      AssistantPrompts.resolve(
        currentPrompt = custom,
        mode = AssistantMode.ACTIONS,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    assertTrue(resolved.startsWith(custom))
    assertTrue(resolved.contains("Current mode: DEVICE."))
  }

  @Test
  fun resolve_doesNotStackModeBlocksWhenTheModeChangesRepeatedly() {
    val custom = "You are Jarvis."
    var resolved =
      AssistantPrompts.resolve(
        currentPrompt = custom,
        mode = AssistantMode.ACTIONS,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    // Feeding the resolved prompt back in is what happens after the user saves it in the editor.
    resolved =
      AssistantPrompts.resolve(
        currentPrompt = resolved,
        mode = AssistantMode.MEDIA,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    resolved =
      AssistantPrompts.resolve(
        currentPrompt = resolved,
        mode = AssistantMode.CHAT,
        hasMcpTools = false,
        webSearchEnabled = false,
      )

    assertEquals(1, Regex("=== MODE ===").findAll(resolved).count())
    assertTrue(resolved.contains("Current mode: CHAT."))
    assertFalse(resolved.contains("Current mode: DEVICE."))
    assertTrue(resolved.startsWith(custom))
  }

  @Test
  fun resolve_treatsABlankPromptAsGenerated() {
    val resolved =
      AssistantPrompts.resolve(
        currentPrompt = "",
        mode = AssistantMode.AUTO,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    assertTrue(AssistantPrompts.isGenerated(resolved))
  }

  @Test
  fun fromId_fallsBackToAutoForUnknownOrMissingIds() {
    assertEquals(AssistantMode.AUTO, AssistantMode.fromId(null))
    assertEquals(AssistantMode.AUTO, AssistantMode.fromId("nonsense"))
    assertEquals(AssistantMode.RESEARCH, AssistantMode.fromId("research"))
  }

  /**
   * The ordering that decides whether skills are used at all.
   *
   * A small model follows the top of its prompt and the very end of it, and stops reading for
   * intent in between. The prompt used to lead with "answer directly from what you know" and to
   * end - in the mode block, the last thing before the conversation - with "answer directly when
   * you already know the answer". With a query-wikipedia skill installed and its tools registered,
   * asking the assistant to check Wikipedia produced "I cannot check Wikipedia": the model was
   * obeying the prompt exactly. Nothing about the wiring was wrong, so nothing about the wiring
   * would have shown it.
   *
   * These assertions are about position, not wording, because position is what failed.
   */
  @Test
  fun tellsTheModelToCheckSkillsBeforeAnsweringFromMemory() {
    val prompt =
      AssistantPrompts.resolve(
        currentPrompt = "",
        mode = AssistantMode.AUTO,
        hasMcpTools = false,
        webSearchEnabled = false,
      )

    val routing = prompt.indexOf("WHAT TO DO WITH EVERY MESSAGE")
    val skills = prompt.indexOf("SKILLS")
    assertTrue("the routing procedure must be present", routing >= 0)
    assertTrue("the skills list must be present", skills >= 0)

    // The routing procedure comes before the capability descriptions.
    assertTrue(
      "routing must come before the capability list",
      routing < prompt.indexOf("=== WHAT YOU CAN DO ==="),
    )

    // Within the routing procedure, loading a skill is ordered before answering from memory.
    val loadSkill = prompt.indexOf("load_skill", routing)
    val answerDirectly = prompt.indexOf("ANSWER DIRECTLY", routing)
    assertTrue("load_skill must be mentioned in the routing procedure", loadSkill >= 0)
    assertTrue("answering directly must be the last step", answerDirectly > loadSkill)
  }

  /** The mode block is appended last, so it must not reopen what the routing closed. */
  @Test
  fun theAutoModeBlockDoesNotPutAnsweringFromMemoryFirst() {
    val prompt =
      AssistantPrompts.resolve(
        currentPrompt = "",
        mode = AssistantMode.AUTO,
        hasMcpTools = false,
        webSearchEnabled = false,
      )
    val modeBlock = prompt.substring(prompt.indexOf("Current mode: AUTO"))
    val skillFirst = modeBlock.indexOf("load_skill")
    val memoryLast = modeBlock.indexOf("Answer directly from your own knowledge")
    assertTrue("the auto mode block should mention load_skill", skillFirst >= 0)
    assertTrue("answering from memory must come after the tool options", memoryLast > skillFirst)
  }
}
