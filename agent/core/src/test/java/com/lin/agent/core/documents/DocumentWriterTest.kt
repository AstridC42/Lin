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

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writing documents, and reading back what was written. */
class DocumentWriterTest {

  private fun entriesOf(bytes: ByteArray): Map<String, String> {
    val found = mutableMapOf<String, String>()
    ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        found[entry.name] = zip.readBytes().decodeToString()
      }
    }
    return found
  }

  private fun docxBytes(text: String) =
    ByteArrayOutputStream().also { writeDocx(text, it) }.toByteArray()

  // ---- the test that matters most ----

  /**
   * Written by this app, read by this app, unchanged.
   *
   * The reader and the writer were built from the same understanding of the format, so if that
   * understanding is wrong they can agree with each other and still both be wrong about Word.
   * This does not catch that - only Word can. What it does catch is the two of them drifting
   * apart, and every escaping and paragraph mistake in between.
   */
  @Test
  fun aDocumentSurvivesBeingWrittenAndReadBack() {
    val text = "A heading\n\nA paragraph with \"quotes\", an & ampersand, and <angles>.\nA line."
    val entries = entriesOf(docxBytes(text))
    assertEquals(text, textFromDocxXml(entries.getValue("word/document.xml")))
  }

  @Test
  fun theArchiveHasExactlyWhatTheFormatRequires() {
    val entries = entriesOf(docxBytes("Hi."))
    assertTrue(entries.containsKey("[Content_Types].xml"))
    assertTrue(entries.containsKey("_rels/.rels"))
    assertTrue(entries.containsKey("word/document.xml"))
  }

  @Test
  fun everyRunPreservesItsSpaces() {
    // Without xml:space, Word may drop the leading space of this line.
    assertTrue(docxBody(" indented").contains("""xml:space="preserve""""))
  }

  @Test
  fun aBlankLineIsAnEmptyParagraphRatherThanNothing() {
    assertTrue(docxBody("A\n\nB").contains("<w:p/>"))
  }

  // ---- escaping ----

  @Test
  fun markupInTheTextCannotBreakTheDocument() {
    val body = docxBody("</w:t></w:r></w:p><w:p><w:r><w:t>injected")
    // The payload survives as text, so reading it back yields the literal line.
    assertEquals(
      "</w:t></w:r></w:p><w:p><w:r><w:t>injected",
      textFromDocxXml(body),
    )
  }

  /** XML 1.0 cannot carry most control characters even as numeric references. */
  @Test
  fun controlCharactersAreDroppedRatherThanWrittenAsCorruptXml() {
    val escaped = escapeXml("before\u000Cafter\u0000end")
    assertEquals("beforeafterend", escaped)
    assertFalse(escaped.contains("\u000C"))
  }

  @Test
  fun tabsAndNewlinesAreKept() {
    assertEquals("a\tb\nc", escapeXml("a\tb\nc"))
  }

  // ---- naming ----

  /**
   * The name comes from a model, so it can be a path.
   *
   * Written as given, "../../shared_prefs/x" would leave Lin's own directory. Every separator
   * goes rather than being escaped.
   */
  @Test
  fun aNameCannotEscapeTheDirectory() {
    assertEquals("x.docx", fileNameFor("../../shared_prefs/x", DocFormat.DOCX))
    assertEquals("passwd.docx", fileNameFor("/etc/passwd", DocFormat.DOCX))
    assertEquals("evil.docx", fileNameFor("..\\..\\evil", DocFormat.DOCX))
    for (name in listOf("../x", "a/b/c", "..", "/", "")) {
      val written = fileNameFor(name, DocFormat.DOCX)
      assertFalse("$name became $written", written.contains("/"))
      assertFalse("$name became $written", written.contains("\\"))
      assertFalse("$name became $written", written.startsWith(".."))
    }
  }

  @Test
  fun anEmptyOrUselessNameStillProducesAFile() {
    assertEquals("Document.docx", fileNameFor("", DocFormat.DOCX))
    assertEquals("Document.pdf", fileNameFor("   ", DocFormat.PDF))
    assertEquals("Document.docx", fileNameFor("///", DocFormat.DOCX))
  }

  @Test
  fun anOrdinaryNameIsLeftRecognisable() {
    assertEquals("Rent agreement (2026).docx", fileNameFor("Rent agreement (2026)", DocFormat.DOCX))
    assertEquals("Notes.md", fileNameFor("Notes.txt", DocFormat.MARKDOWN))
  }

  @Test
  fun aVeryLongNameIsCutRatherThanRefused() {
    val written = fileNameFor("x".repeat(500), DocFormat.TXT)
    assertTrue(written.length < 100)
    assertTrue(written.endsWith(".txt"))
  }

  // ---- which format ----

  @Test
  fun theFormatIsTakenFromWhatWasAskedFor() {
    assertEquals(DocFormat.PDF, formatFor("pdf"))
    assertEquals(DocFormat.PDF, formatFor(".PDF"))
    assertEquals(DocFormat.TXT, formatFor("plain text".substringAfter(' ')))
    assertEquals(DocFormat.MARKDOWN, formatFor("markdown"))
  }

  @Test
  fun theNameDecidesWhenNoFormatWasAsked() {
    assertEquals(DocFormat.PDF, formatFor(null, "invoice.pdf"))
    assertEquals(DocFormat.TXT, formatFor(null, "notes.txt"))
  }

  /** Word, because it is the one a person can open and then edit. */
  @Test
  fun theDefaultIsSomethingTheUserCanEdit() {
    assertEquals(DocFormat.DOCX, formatFor(null, ""))
    assertEquals(DocFormat.DOCX, formatFor("something else", "file.xyz"))
  }
}
