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
import org.junit.Assert.assertNull
import org.junit.Test

class PageImageTest {

  @Test
  fun `the page's own preview picture is found`() {
    val html =
      """<html><head><title>Orin</title>
      <meta name="description" content="A computer">
      <meta content="https://cdn.example.com/orin.jpg?w=1200&amp;q=80" property="og:image">
      </head><body>…</body></html>"""
    assertEquals("https://cdn.example.com/orin.jpg?w=1200&q=80", pageImage(html, "https://example.com/orin"))
  }

  @Test
  fun `twitter's name for it works, and a relative address is made whole`() {
    val html = """<meta name='twitter:image' content='/img/card.png'>"""
    assertEquals("https://example.com/img/card.png", pageImage(html, "https://example.com/a/b"))
  }

  @Test
  fun `icons, logos and pages without a picture give nothing`() {
    assertNull(pageImage("""<meta property="og:image" content="https://x.com/logo.svg">""", "https://x.com"))
    assertNull(pageImage("""<meta property="og:image" content="https://x.com/favicon.ico">""", "https://x.com"))
    assertNull(pageImage("""<meta property="og:image" content="data:image/png;base64,AAA">""", "https://x.com"))
    assertNull(pageImage("""<meta property="og:title" content="https://x.com/a.jpg">""", "https://x.com"))
    assertNull(pageImage("<html><body>no meta</body></html>", "https://x.com"))
  }

  @Test
  fun `a page sent as markdown gives its first real picture`() {
    val md = "# Title\n![logo](https://x.com/logo.png)\nText\n![Board](https://x.com/board.jpg \"Orin\")"
    assertEquals("https://x.com/board.jpg", markdownImage(md, "https://x.com/p"))
  }
}
