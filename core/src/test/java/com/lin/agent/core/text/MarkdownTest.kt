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

/**
 * Reading a model's Markdown without losing any of it.
 *
 * The rule under every test here: a marker that does not pair up stays on screen as text. An
 * assistant that swallows half a sentence because it contained a lone asterisk is worse than
 * one that shows the asterisk.
 */
class MarkdownTest {

  private fun text(blocks: List<Block>): String =
    blocks.joinToString("\n") { block ->
      when (block) {
        is Block.Paragraph -> block.spans.plain()
        is Block.Heading -> block.spans.plain()
        is Block.Bullet -> block.spans.plain()
        is Block.Numbered -> block.spans.plain()
        is Block.Quote -> block.spans.plain()
        is Block.Code -> block.text
      }
    }

  private fun List<Span>.plain() = joinToString("") { it.text }

  // ---- blocks ----

  @Test
  fun `a plain reply is one paragraph`() {
    val blocks = markdown("It is half past ten.")
    assertEquals(listOf(Block.Paragraph(listOf(Span("It is half past ten.")))), blocks)
  }

  @Test
  fun `a blank line separates paragraphs, a single newline does not`() {
    // Inside a paragraph a newline is a soft wrap. Keeping it would put the model's own line
    // breaks into a column of a different width.
    val blocks = markdown("One line\nwrapped oddly.\n\nA second thought.")
    assertEquals(2, blocks.size)
    assertEquals("One line wrapped oddly.", (blocks[0] as Block.Paragraph).spans.plain())
  }

  @Test
  fun `headings, bullets and numbers`() {
    val blocks =
      markdown(
        """
        ## Today

        - milk
        - bread

        1. ring the dentist
        2. pay the bill
        """
          .trimIndent()
      )
    assertEquals(Block.Heading(2, listOf(Span("Today"))), blocks[0])
    assertEquals(Block.Bullet(listOf(Span("milk"))), blocks[1])
    assertEquals(2, (blocks[4] as Block.Numbered).number)
    assertEquals("pay the bill", (blocks[4] as Block.Numbered).spans.plain())
  }

  @Test
  fun `a nested bullet keeps its indent`() {
    val blocks = markdown("- one\n    - deeper")
    assertEquals(0, (blocks[0] as Block.Bullet).indent)
    assertEquals(2, (blocks[1] as Block.Bullet).indent)
  }

  @Test
  fun `a fenced block is verbatim, markers and blank lines included`() {
    val blocks = markdown("Try:\n\n```\n- not a bullet\n\n**not bold**\n```\n\nThat is all.")
    val code = blocks.filterIsInstance<Block.Code>().single()
    assertEquals("- not a bullet\n\n**not bold**", code.text)
    assertEquals(3, blocks.size)
  }

  @Test
  fun `a fence the model never closed is still code`() {
    // Dropping it loses the answer; reading it as prose loses its shape.
    val blocks = markdown("Run this:\n\n```\nollama serve")
    assertEquals("ollama serve", blocks.filterIsInstance<Block.Code>().single().text)
  }

  // ---- inline ----

  @Test
  fun `bold and italic and code`() {
    val spans = spansOf("A **firm** and *soft* and `exact` word.")
    assertTrue(spans.any { it.text == "firm" && it.bold })
    assertTrue(spans.any { it.text == "soft" && it.italic })
    assertTrue(spans.any { it.text == "exact" && it.code })
  }

  @Test
  fun `nothing inside code is a marker`() {
    // The whole point of showing code is that it is shown as written.
    val spans = spansOf("Set `OLLAMA_HOST=0.0.0.0:11434 **here**` first.")
    val code = spans.single { it.code }
    assertEquals("OLLAMA_HOST=0.0.0.0:11434 **here**", code.text)
  }

  @Test
  fun `an underscore inside a word is part of the word`() {
    // The same case forSpeaking has to get right: OLLAMA_HOST is not emphasis, and a reply
    // that renders it as "OLLAMAHOST" in italics has changed the one thing the user needed.
    val spans = spansOf("Set OLLAMA_HOST and MODEL_NAME before starting.")
    assertEquals("Set OLLAMA_HOST and MODEL_NAME before starting.", spans.joinToString("") { it.text })
    assertTrue(spans.none { it.italic })
  }

  @Test
  fun `an unpaired marker is text`() {
    assertEquals("2 * 3 * 4 = 24", spansOf("2 * 3 * 4 = 24").filter { !it.italic }.joinToString("") { it.text })
    assertEquals("A lone ** stays", spansOf("A lone ** stays").joinToString("") { it.text })
    assertEquals("half `open", spansOf("half `open").joinToString("") { it.text })
  }

  @Test
  fun `a sentence that opens bold and never closes it is still readable`() {
    val spans = spansOf("**Careful: this is the whole sentence")
    assertEquals("**Careful: this is the whole sentence", spans.joinToString("") { it.text })
  }

  @Test
  fun `no character is lost from a whole reply`() {
    // The property that matters more than any formatting: everything the model wrote reaches
    // the screen, with or without its decoration.
    val reply =
      """
      ## Steps

      1. Open **settings**
      2. Set `OLLAMA_HOST=0.0.0.0`

      Then it works - 100% of the time * mostly.
      """
        .trimIndent()
    val shown = text(markdown(reply))
    for (word in listOf("Steps", "Open", "settings", "OLLAMA_HOST=0.0.0.0", "100%", "mostly")) {
      assertTrue(word, shown.contains(word))
    }
  }
}

class MarkdownLinkTest {

  @Test
  fun `a markdown link becomes a tappable run with its own words`() {
    val spans = spansOf("See [the product page](https://www.nvidia.com/orin) for more.")
    assertEquals(
      listOf(
        Span("See "),
        Span("the product page", url = "https://www.nvidia.com/orin"),
        Span(" for more."),
      ),
      spans,
    )
  }

  @Test
  fun `a bare address is a link, without the full stop that ends the sentence`() {
    val spans = spansOf("1. https://www.nvidia.com/en-us/autonomous-machines/.")
    assertEquals("https://www.nvidia.com/en-us/autonomous-machines/", spans.single { it.url.isNotEmpty() }.url)
    assertEquals(".", spans.last().text)
  }

  @Test
  fun `underscores and stars inside an address are not emphasis`() {
    val url = "https://example.com/a_b_c*d"
    val spans = spansOf("go to $url now")
    assertEquals(url, spans.single { it.url.isNotEmpty() }.text)
    assertTrue(spans.none { it.italic || it.bold })
  }

  @Test
  fun `a bracket belongs to the address only when the address opened one`() {
    assertEquals("https://en.wikipedia.org/wiki/Tegra_(chip)", bareUrl("https://en.wikipedia.org/wiki/Tegra_(chip)"))
    assertEquals("https://example.com", bareUrl("https://example.com)"))
  }

  @Test
  fun `a footnote mark is not a link`() {
    // "[1]" in a numbered source list, or brackets in ordinary prose, stay text.
    assertEquals(listOf(Span("[1] Jetson [beta]")), spansOf("[1] Jetson [beta]"))
    assertEquals(listOf(Span("[label](not a link)")), spansOf("[label](not a link)"))
  }

  @Test
  fun `an address in the middle of a word is left alone`() {
    assertTrue(spansOf("xhttps://example.com").none { it.url.isNotEmpty() })
  }
}
