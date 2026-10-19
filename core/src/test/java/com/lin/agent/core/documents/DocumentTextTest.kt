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

package com.lin.agent.core.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a Word document, which is a zip of XML that does not want to be read this way. */
class DocumentTextTest {

  private fun docx(body: String) =
    """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>$body</w:body></w:document>"""

  // ---- the failure this function exists to avoid ----

  /**
   * Word splits one word across runs whenever anything changes mid-word.
   *
   * A spell-check mark or a tracked-change boundary is enough. Any approach that joins runs
   * with a space turns "assistant" into "assi st an t", and it is the single most likely way
   * for this to be subtly wrong on real documents rather than on test fixtures.
   */
  @Test
  fun aWordSplitAcrossRunsComesBackWhole() {
    val xml =
      docx("<w:p><w:r><w:t>assi</w:t></w:r><w:r><w:t>st</w:t></w:r><w:r><w:t>ant</w:t></w:r></w:p>")
    assertEquals("assistant", textFromDocxXml(xml))
  }

  @Test
  fun paragraphsBecomeLines() {
    val xml = docx("<w:p><w:r><w:t>One.</w:t></w:r></w:p><w:p><w:r><w:t>Two.</w:t></w:r></w:p>")
    assertEquals("One.\nTwo.", textFromDocxXml(xml))
  }

  @Test
  fun tabsAndBreaksSurvive() {
    val xml = docx("<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>")
    assertEquals("A\tB\nC", textFromDocxXml(xml))
  }

  // ---- what must not come back ----

  /** Field codes are instructions to Word, and read as gibberish mid-sentence. */
  @Test
  fun fieldCodesAreNotProse() {
    val xml =
      docx(
        "<w:p><w:r><w:t>See page </w:t></w:r>" +
          "<w:r><w:instrText> PAGEREF _Ref12345 \\h </w:instrText></w:r>" +
          "<w:r><w:t>four.</w:t></w:r></w:p>"
      )
    val text = textFromDocxXml(xml)
    assertEquals("See page four.", text)
    assertFalse(text.contains("PAGEREF"))
  }

  /**
   * Deleted text is still in the file when tracked changes are on.
   *
   * Including it would have Lin answer from a draft the author explicitly rejected, which is
   * worse than not reading the document at all.
   */
  @Test
  fun textSomebodyDeletedStaysDeleted() {
    val xml =
      docx(
        "<w:p><w:r><w:t>The deal is </w:t></w:r>" +
          "<w:del><w:r><w:delText>off</w:delText></w:r></w:del>" +
          "<w:ins><w:r><w:t>on</w:t></w:r></w:ins>" +
          "<w:r><w:t>.</w:t></w:r></w:p>"
      )
    val text = textFromDocxXml(xml)
    assertEquals("The deal is on.", text)
    assertFalse(text.contains("off"))
  }

  @Test
  fun nothingBreaksOnTruncatedOrEmptyXml() {
    assertEquals("", textFromDocxXml(""))
    assertEquals("", textFromDocxXml("<w:document><w:body>"))
    assertEquals("", textFromDocxXml("<w:p><w:r><w:t>unclosed"))
  }

  // ---- entities ----

  @Test
  fun namedEntitiesAreResolved() {
    assertEquals("Marks & Spencer", unescapeXml("Marks &amp; Spencer"))
    assertEquals("""a<b>c"d'e""", unescapeXml("a&lt;b&gt;c&quot;d&apos;e"))
  }

  @Test
  fun numericEntitiesAreResolved() {
    assertEquals("A", unescapeXml("&#65;"))
    assertEquals("A", unescapeXml("&#x41;"))
  }

  /** More likely an ampersand somebody typed than a form this should have handled. */
  @Test
  fun anAmpersandThatIsNotAnEntityIsLeftAlone() {
    assertEquals("Tom & Jerry", unescapeXml("Tom & Jerry"))
    assertEquals("R&D; the department", unescapeXml("R&D; the department"))
  }

  // ---- what kind of file is this ----

  @Test
  fun theExtensionDecidesBeforeTheMimeType() {
    // Both of these are what sharing apps really send.
    assertEquals(DocKind.DOCX, kindOf("report.docx", "application/octet-stream"))
    assertEquals(DocKind.DOCX, kindOf("report.docx", "text/plain"))
    assertEquals(DocKind.PDF, kindOf("invoice.PDF", null))
    assertEquals(DocKind.TEXT, kindOf("notes.md", null))
  }

  @Test
  fun theMimeTypeIsUsedWhenTheNameSaysNothing() {
    assertEquals(DocKind.PDF, kindOf("download", "application/pdf"))
    assertEquals(DocKind.TEXT, kindOf("download", "text/csv"))
    assertEquals(
      DocKind.DOCX,
      kindOf(
        "download",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      ),
    )
  }

  /** .doc is a different, binary format. Reading it as a zip yields nothing. */
  @Test
  fun anOldWordFileIsNamedAsUnreadableRatherThanTriedAnyway() {
    assertEquals(DocKind.UNKNOWN, kindOf("legacy.doc", null))
    assertEquals(DocKind.UNKNOWN, kindOf("archive.zip", null))
  }

  // ---- what gets sent to a model ----

  @Test
  fun blankRunsCollapseAndTrailingSpaceGoes() {
    assertEquals("A\n\nB", tidy("A  \n\n\n\n   \nB\n\n"))
  }

  @Test
  fun aShortDocumentIsSentWhole() {
    assertEquals("Short.", forReading("Short.", limit = 100))
  }

  @Test
  fun aLongDocumentIsCutAndSaysSo() {
    val long = (1..500).joinToString("\n") { "Line $it is here." }
    val cut = forReading(long, limit = 200)
    assertTrue(cut.length < long.length)
    assertTrue(cut.endsWith("[This is the first part of a longer document.]"))
    // Cut at a line rather than mid-word.
    assertTrue(cut.lines()[cut.lines().size - 3].endsWith("."))
  }
}
