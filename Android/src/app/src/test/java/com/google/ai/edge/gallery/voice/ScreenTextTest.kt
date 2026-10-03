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

package com.google.ai.edge.gallery.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding how much of the screen behind the panel is worth sending.
 *
 * The platform hands over the whole view hierarchy of the app in front, and taken literally that
 * is hundreds of nodes: every label, every button, the same string once per recycled list row, and
 * a lot of furniture nobody would read aloud. Sent as it comes it would be several thousand tokens
 * of mostly nothing, out of a budget already shared with the tools, the memory and the
 * conversation - so the cap here is the difference between a useful answer and no room to think.
 */
class ScreenTextTest {

  @Test
  fun keepsTheLinesInReadingOrder() {
    assertEquals(
      "First paragraph\nSecond paragraph",
      ScreenText.summarize(listOf("First paragraph", "Second paragraph")),
    )
  }

  /** A recycled list shows one label many times; the model should see it once. */
  @Test
  fun showsARepeatedLabelOnce() {
    val text = ScreenText.summarize(listOf("Inbox", "Inbox", "Inbox", "Drafts"))
    assertEquals("Inbox\nDrafts", text)
  }

  /** Two lines differing only in case are usually two different pieces of text. */
  @Test
  fun treatsDifferentCasingAsDifferentText() {
    val text = ScreenText.summarize(listOf("Send", "SEND"))
    assertEquals("Send\nSEND", text)
  }

  @Test
  fun dropsFurnitureAndBlanks() {
    val text = ScreenText.summarize(listOf("", "  ", "A", "1", "Real content here"))
    assertEquals("Real content here", text)
  }

  @Test
  fun collapsesWhitespaceInsideALine() {
    assertEquals("one two three", ScreenText.summarize(listOf("one   two\n three")))
  }

  /** The cap holds, and it cuts between lines rather than through one. */
  @Test
  fun stopsAtTheCapOnALineBoundary() {
    val lines = (1..100).map { "line number $it with some words after it" }
    val text = ScreenText.summarize(lines, maxChars = 100)
    assertTrue("must respect the cap, was ${text.length}", text.length <= 100)
    assertTrue("must not cut a line in half", text.lines().all { it in lines })
  }

  /**
   * A single paragraph longer than the whole cap still produces something. Returning nothing
   * because the first line did not fit would be worst on exactly the screens people most want
   * read to them - an article, a long message.
   */
  @Test
  fun truncatesASingleOversizeLineRatherThanDroppingIt() {
    val long = "word ".repeat(200)
    val text = ScreenText.summarize(listOf(long), maxChars = 50)
    assertEquals(50, text.length)
  }

  @Test
  fun givesNothingBackForNothing() {
    assertEquals("", ScreenText.summarize(listOf()))
    assertEquals("", ScreenText.summarize(listOf("", " ", "x")))
  }
}
