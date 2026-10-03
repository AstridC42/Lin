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

package com.google.ai.edge.gallery.skills

import com.google.ai.edge.gallery.proto.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the installed skills are written into the system prompt.
 *
 * This list is the only thing standing between "look this up on Wikipedia" and an answer invented
 * from the model's own training. It failed at that once already, and not because the skill was
 * missing: it was installed, enabled and listed, but listed as a name and a description under
 * headings, several sections below the instruction to use it. So the shape of these lines is the
 * behaviour, and is worth pinning down.
 */
class SkillListFormatTest {

  private fun skill(name: String, description: String, selected: Boolean = true): Skill =
    Skill.newBuilder().setName(name).setDescription(description).setSelected(selected).build()

  /** The exact call is on the same line as the reason to make it. */
  @Test
  fun writesEachSkillAsTheCallThatLoadsIt() {
    val text =
      formatSelectedSkills(listOf(skill("query-wikipedia", "Query a summary from Wikipedia.")))
    assertEquals("- load_skill(\"query-wikipedia\") - Query a summary from Wikipedia.", text)
  }

  /** A skill the user switched off must not be offered; calling it would only fail. */
  @Test
  fun leavesOutSkillsThatAreTurnedOff() {
    val text =
      formatSelectedSkills(
        listOf(
          skill("query-wikipedia", "Query a summary from Wikipedia."),
          skill("send-email", "Send an email.", selected = false),
        )
      )
    assertTrue(text.contains("query-wikipedia"))
    assertFalse(text.contains("send-email"))
  }

  /** One skill, one line - the whole point of the format. */
  @Test
  fun keepsOneSkillToOneLine() {
    val text =
      formatSelectedSkills(
        listOf(
          skill("query-wikipedia", "Query a summary from Wikipedia."),
          skill("qr-code", "Make a QR code."),
          skill("interactive-map", "Show a map."),
        )
      )
    assertEquals(3, text.lines().size)
  }

  /**
   * A description that runs on is trimmed rather than allowed to push the conversation out of the
   * context window - twelve skills at full length is most of a small model's budget.
   */
  @Test
  fun trimsADescriptionThatRunsOn() {
    val long = "A ".repeat(200) + "end"
    val text = formatSelectedSkills(listOf(skill("verbose", long)))
    assertEquals(1, text.lines().size)
    assertTrue("the line must stay short", text.length < 200)
    assertTrue("the trimming must be visible", text.endsWith("..."))
    assertTrue("the name must survive the trim", text.contains("load_skill(\"verbose\")"))
  }

  /** A multi-line description collapses to its first line rather than breaking the list. */
  @Test
  fun collapsesAMultiLineDescription() {
    val text = formatSelectedSkills(listOf(skill("multi", "First line.\nSecond line.")))
    assertEquals("- load_skill(\"multi\") - First line.", text)
  }

  @Test
  fun handlesNoSkillsAtAll() {
    assertEquals("", formatSelectedSkills(listOf()))
  }
}
