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

class ReadableTest {

  @Test
  fun `tags go and the words stay`() {
    assertEquals("Hello there", readableText("<p>Hello <b>there</b></p>"))
  }

  @Test
  fun `script contents never become text`() {
    // The order of the steps is the whole design: stripping tags before removing script
    // bodies would leave the JavaScript behind as prose.
    val text = readableText("<p>Real words</p><script>var secret = 'not words';</script>")
    assertFalse(text, text.contains("secret"))
    assertFalse(text, text.contains("var"))
    assertTrue(text, text.contains("Real words"))
  }

  @Test
  fun `style and svg and forms go too`() {
    val html =
      "<style>.a{color:red}</style><svg><path d='M0 0'/></svg>" +
        "<form><input value='x'></form><p>Only this</p>"
    assertEquals("Only this", readableText(html))
  }

  @Test
  fun `a comment cannot confuse what follows`() {
    // A commented-out block can contain unbalanced tags, which would otherwise swallow the
    // rest of the page when the tag stripper reached them.
    assertEquals("After", readableText("<!-- <div><p>hidden --><p>After</p>"))
  }

  @Test
  fun `blocks become line breaks so sentences do not run together`() {
    assertEquals("One\nTwo", readableText("<p>One</p><p>Two</p>"))
    assertEquals("One\nTwo", readableText("<div>One</div><div>Two</div>"))
    assertEquals("One\nTwo", readableText("One<br>Two"))
  }

  @Test
  fun `list items are marked`() {
    assertTrue(readableText("<ul><li>Milk<li>Bread</ul>").contains("• Milk"))
  }

  @Test
  fun `the article is preferred when the page says where it is`() {
    val html =
      "<nav>Home About Contact</nav>" +
        "<article>" +
        "The actual story goes here and is much longer than the navigation beside it, ".repeat(6) +
        "</article>" +
        "<aside>Related links</aside>"
    val text = readableText(html)
    assertTrue(text, text.contains("actual story"))
    assertFalse(text, text.contains("Related links"))
  }

  @Test
  fun `a tiny article does not throw the page away`() {
    // A site that wraps a breadcrumb in <article> and the story in a div would otherwise
    // have Lin read the breadcrumb and nothing else - worse than reading everything.
    val html = "<article>Ad</article><div>" + "The real story. ".repeat(200) + "</div>"
    val text = readableText(html)
    assertTrue(text, text.contains("The real story"))
    // Stated as its own assertion rather than left implicit in the one above: the failure
    // being guarded against is the article winning, and "Ad" alone is what that looks like.
    assertFalse(text, text.trim() == "Ad")
  }

  @Test
  fun `entities become the characters they stand for`() {
    assertEquals("Tom & Jerry", readableText("<p>Tom &amp; Jerry</p>"))
    assertEquals("\"quoted\"", readableText("<p>&quot;quoted&quot;</p>"))
    assertEquals("10° — 20°", readableText("<p>10&deg; &mdash; 20&deg;</p>"))
  }

  @Test
  fun `numeric escapes decode`() {
    assertEquals("café", readableText("<p>caf&#233;</p>"))
    assertEquals("café", readableText("<p>caf&#xE9;</p>"))
  }

  @Test
  fun `a page showing example markup still shows it`() {
    // &amp; is decoded last, so "&amp;lt;" becomes "&lt;" rather than "<".
    assertEquals("&lt;p&gt;", readableText("<p>&amp;lt;p&amp;gt;</p>"))
  }

  @Test
  fun `a stray ampersand-hash is left as written`() {
    assertEquals("issue &#1 and &#xZZ;", readableText("<p>issue &#1 and &#xZZ;</p>"))
  }

  @Test
  fun `runs of blank lines collapse`() {
    val text = readableText("<p>One</p><div></div><div></div><div></div><p>Two</p>")
    assertFalse(text, text.contains("\n\n\n"))
  }

  @Test
  fun `non-breaking spaces are spaces`() {
    assertEquals("a b", readableText("<p>a&nbsp;b</p>"))
  }

  @Test
  fun `a long page is cut at a word with a mark`() {
    // The limit deliberately lands in the middle of a word - 102 characters of "word " is
    // twenty whole ones and then "wo" - because a limit that happens to fall on a boundary
    // passes whether or not the cut is word-aware, which is not a test of anything.
    val text = readableText("<p>" + "word ".repeat(10_000) + "</p>", limit = 102)
    assertTrue("${text.length}", text.length <= 103)
    assertTrue(text, text.endsWith("…"))
    assertTrue(text, text.removeSuffix("…").trim().endsWith("word"))
  }

  @Test
  fun `a short page is not cut`() {
    assertEquals("Short", readableText("<p>Short</p>", limit = 100))
  }

  @Test
  fun `nothing readable is empty rather than a crash`() {
    assertEquals("", readableText(""))
    assertEquals("", readableText("<script>only()</script>"))
  }

  @Test
  fun `text that is not html at all comes through`() {
    assertEquals("Just a sentence.", readableText("Just a sentence."))
  }
}
