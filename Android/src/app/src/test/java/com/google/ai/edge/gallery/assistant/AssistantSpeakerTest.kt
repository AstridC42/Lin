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

class AssistantSpeakerTest {

  @Test
  fun toSpeakableText_dropsMarkdownSyntax() {
    assertEquals(
      "It is 18 degrees in Berlin.",
      AssistantSpeaker.toSpeakableText("It is **18 degrees** in _Berlin_."),
    )
  }

  @Test
  fun toSpeakableText_readsLinkTextNotUrls() {
    assertEquals(
      "See the forecast for details.",
      AssistantSpeaker.toSpeakableText("See [the forecast](https://example.org/a?b=c) for details."),
    )
  }

  @Test
  fun toSpeakableText_dropsCodeBlocksAndKeepsInlineCode() {
    assertEquals(
      "Run npm install first.",
      AssistantSpeaker.toSpeakableText("Run `npm install` first.\n\n```\nsome code\n```"),
    )
  }

  @Test
  fun toSpeakableText_flattensHeadingsAndBullets() {
    assertEquals(
      "Plan Buy milk Call Sam",
      AssistantSpeaker.toSpeakableText("## Plan\n- Buy milk\n- Call Sam"),
    )
  }

  @Test
  fun toSpeakableText_returnsEmptyForContentWithNothingToSay() {
    assertEquals("", AssistantSpeaker.toSpeakableText("```\ncode only\n```"))
    assertEquals("", AssistantSpeaker.toSpeakableText("   \n\n  "))
  }

  @Test
  fun toSpeakableText_capsVeryLongReplies() {
    val spoken = AssistantSpeaker.toSpeakableText("word ".repeat(3000))
    assertTrue(spoken.length <= 4000)
  }
}
