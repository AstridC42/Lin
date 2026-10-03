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

package com.google.ai.edge.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngClientTest {

  @Test
  fun normalizeBaseUrl_addsHttpsToBareHost() {
    assertEquals("https://searx.example.org", SearxngClient.normalizeBaseUrl("searx.example.org"))
  }

  @Test
  fun normalizeBaseUrl_keepsExplicitScheme() {
    assertEquals("http://192.168.1.5:8080", SearxngClient.normalizeBaseUrl("http://192.168.1.5:8080"))
  }

  @Test
  fun normalizeBaseUrl_stripsTrailingSlashesAndWhitespace() {
    assertEquals(
      "https://searx.example.org",
      SearxngClient.normalizeBaseUrl("  https://searx.example.org/  "),
    )
  }

  @Test
  fun normalizeBaseUrl_rejectsBlank() {
    assertNull(SearxngClient.normalizeBaseUrl(""))
    assertNull(SearxngClient.normalizeBaseUrl("   "))
    assertNull(SearxngClient.normalizeBaseUrl("/"))
  }

  @Test
  fun htmlToPlainText_dropsScriptsAndStyles() {
    val text =
      SearxngClient.htmlToPlainText(
        "<html><head><style>p{color:red}</style></head>" +
          "<body><script>alert('x')</script><p>Real content</p></body></html>"
      )
    assertEquals("Real content", text)
  }

  @Test
  fun htmlToPlainText_putsEachBlockOnItsOwnLine() {
    assertEquals("First\nSecond", SearxngClient.htmlToPlainText("<p>First</p><p>Second</p>"))
    assertEquals("One\nTwo", SearxngClient.htmlToPlainText("<ul><li>One</li><li>Two</li></ul>"))
    assertEquals("Line\nBreak", SearxngClient.htmlToPlainText("Line<br/>Break"))
  }

  @Test
  fun htmlToPlainText_collapsesLongRunsOfBlankLines() {
    val text = SearxngClient.htmlToPlainText("<p>First</p>\n\n\n\n\n<p>Second</p>")
    assertEquals("First\n\nSecond", text)
  }

  @Test
  fun htmlToPlainText_unescapesEntities() {
    val text = SearxngClient.htmlToPlainText("<p>Tom &amp; Jerry &mdash; 5 &lt; 6</p>")
    assertEquals("Tom & Jerry — 5 < 6", text)
  }

  @Test
  fun htmlToPlainText_collapsesRuns() {
    val text = SearxngClient.htmlToPlainText("<p>a      b\t\tc</p>")
    assertEquals("a b c", text)
  }

  @Test
  fun htmlToPlainText_keepsBodyTextOfARealisticPage() {
    val html =
      """
      <html><head><title>Weather</title></head>
      <body>
        <nav><a href="/">Home</a></nav>
        <h1>Berlin</h1>
        <p>It is <b>18</b> degrees.</p>
        <footer>copyright</footer>
      </body></html>
      """
    val text = SearxngClient.htmlToPlainText(html)
    assertTrue(text, text.contains("Berlin"))
    assertTrue(text, text.contains("It is 18 degrees."))
    // nav and footer are chrome, not content.
    assertTrue(text, !text.contains("Home"))
    assertTrue(text, !text.contains("copyright"))
  }
}
