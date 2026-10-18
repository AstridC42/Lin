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

package com.lin.agent.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {

  // The shape of a real product page: marketing up top, the specification table far below,
  // and table rows that never mention the product by name.
  private val productPage =
    buildString {
      append("Bring the power of generative AI to the edge.\n")
      repeat(30) { append("Marketing paragraph $it about how exciting and innovative edge computing is for everyone everywhere.\n") }
      append("Jetson Orin Nano Super Developer Kit technical specifications\n")
      append("AI Performance | 67 TOPS\n")
      append("GPU | 1024-core NVIDIA Ampere architecture GPU with 32 Tensor Cores\n")
      append("CPU | 6-core Arm Cortex-A78AE v8.2 64-bit CPU\n")
      append("Memory | 8GB 128-bit LPDDR5 102 GB/s\n")
      append("Power | 7W - 25W\n")
      repeat(30) { append("Footer paragraph $it with legal text and links nobody asked about at all.\n") }
    }

  @Test
  fun `the specification table is found even though its rows never name the product`() {
    // The complaint this exists for: the answer came from the title, because the figures
    // were two thousand words down and the rows do not say "Orin" or "Nano".
    val read = relevantPassages(productPage, "NVIDIA Jetson Orin Nano specs", budget = 1_200)
    assertTrue(read, read.contains("67 TOPS"))
    assertTrue(read, read.contains("1024-core NVIDIA Ampere"))
    assertTrue(read, read.contains("8GB 128-bit LPDDR5"))
  }

  @Test
  fun `the budget is kept`() {
    val read = relevantPassages(productPage, "Orin Nano specifications", budget = 900)
    assertTrue("${read.length}", read.length <= 900 + 10)
  }

  @Test
  fun `the window after a match is bounded, so the footer stays out`() {
    // A line or two may ride along at the window's edge - it cannot know where a table ends -
    // but the footer as a whole must not.
    val read = relevantPassages(productPage, "Orin Nano", budget = 2_000)
    assertFalse(read, read.contains("Footer paragraph 20"))
    assertFalse(read, read.contains("Footer paragraph 29"))
  }

  @Test
  fun `the opening is kept when it is short, so the answer can say what the page is`() {
    val read = relevantPassages(productPage, "Orin Nano", budget = 1_500)
    assertTrue(read, read.startsWith("Bring the power of generative AI to the edge."))
  }

  @Test
  fun `gaps between the parts kept are marked`() {
    // So a model does not read the opening and the table as one continuous paragraph.
    val read = relevantPassages(productPage, "Orin Nano", budget = 1_500)
    assertTrue(read, read.contains("\n…\n"))
  }

  @Test
  fun `a short page comes back whole`() {
    assertEquals("Small page.", relevantPassages("  Small page.  ", "anything", budget = 100))
  }

  @Test
  fun `a page that never mentions the question comes back from the top`() {
    val page = "First line.\n" + "word ".repeat(1000)
    val read = relevantPassages(page, "zebra", budget = 200)
    assertTrue(read, read.startsWith("First line."))
    assertTrue(read, read.endsWith("…"))
  }

  @Test
  fun `a table row is never cut in half`() {
    // Half a row is a figure with no label.
    val rows = "Orin Nano table\n" + (1..80).joinToString("\n") { "Row $it | value $it" }
    val read = relevantPassages(rows, "Orin Nano", budget = 300)
    for (line in read.lines()) {
      assertTrue(line, line == "…" || line == "Orin Nano table" || line.matches(Regex("Row \\d+ \\| value \\d+")))
    }
  }

  @Test
  fun `a menu that appears twice is read once, and bare bullets not at all`() {
    // NVIDIA's product page sends its section menu twice, one per screen size, as forty lines
    // at the very top - exactly where the opening is taken from.
    val lines = listOf("•", "Introduction", "•", "Specs", "•", "Introduction", "•", "Specs", "Real text")
    assertEquals(listOf("Introduction", "Specs", "Real text"), withoutMenus(lines))
  }

  @Test
  fun `a repeated figure in a table is kept`() {
    // A short repeated line is a menu entry; a repeated table row can be the whole point.
    val lines = listOf("Power | 15W", "Mode A", "Power | 15W")
    assertEquals(lines, withoutMenus(lines))
  }

  @Test
  fun `the words matched on leave out filler and years`() {
    assertEquals(listOf("nvidia", "orin", "nano"), termsOf("NVIDIA Orin Nano specs performance 2024"))
  }

  // ---- what the model is handed ----

  private val official = SearchHit("Jetson Orin Nano Super", "https://www.nvidia.com/orin-nano", "Buy now")
  private val wiki = SearchHit("Nvidia Jetson", "https://en.wikipedia.org/?curid=1", "A series of boards")

  @Test
  fun `each source is numbered with its link and what the page says`() {
    val said =
      searchBriefing("orin nano specs", listOf(ReadHit(official, "AI Performance | 67 TOPS"), ReadHit(wiki, "")))
    assertTrue(said, said.contains("[1] Jetson Orin Nano Super\nhttps://www.nvidia.com/orin-nano"))
    assertTrue(said, said.contains("What the page says:\nAI Performance | 67 TOPS"))
    // A page that was not read is marked as such, so a snippet is never mistaken for the page.
    assertTrue(said, said.contains("[2] Nvidia Jetson\nhttps://en.wikipedia.org/?curid=1\nSnippet only (not read): A series of boards"))
  }

  @Test
  fun `the model is told to answer from the pages and keep the links`() {
    // Both halves of the complaint: titles instead of content, and no links.
    val said = searchBriefing("q", listOf(ReadHit(official, "67 TOPS")))
    assertTrue(said, said.contains("not from their titles"))
    assertTrue(said, said.contains("Sources:"))
    assertTrue(said, said.contains("Never drop the links"))
  }

  @Test
  fun `with nothing read the model is told it has only snippets`() {
    val said = searchBriefing("q", listOf(ReadHit(official, "")))
    assertTrue(said, said.contains("only their snippets"))
    assertFalse(said, said.contains("not from their titles"))
    assertTrue(said, said.contains("Sources:"))
  }

  @Test
  fun `the source note is carried`() {
    val said = searchBriefing("q", listOf(ReadHit(wiki, "x")), "Wikipedia only")
    assertTrue(said, said.startsWith("Results for \"q\" (Wikipedia only):"))
  }
}
