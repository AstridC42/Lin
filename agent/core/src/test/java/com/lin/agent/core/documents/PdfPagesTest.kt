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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Laying prose onto a page, and never writing over a file. */
class PdfPagesTest {

  @get:Rule val folder = TemporaryFolder()

  /** Stands in for the font's measurement: a fixed number of characters per line. */
  private fun upTo(characters: Int): (String) -> Boolean = { it.length <= characters }

  @Test
  fun aLineTooLongIsBrokenAtASpace() {
    assertEquals(listOf("one two", "three"), wrap("one two three", upTo(10)))
  }

  @Test
  fun blankLinesBetweenParagraphsSurvive() {
    assertEquals(listOf("a", "", "b"), wrap("a\n\nb", upTo(10)))
  }

  /**
   * A word longer than a line goes on its own line and is allowed to overrun.
   *
   * Hyphenating silently breaks a URL, which is nearly always what this is. A visibly long line
   * is the lesser fault, and it is a fault the user can see.
   */
  @Test
  fun aWordLongerThanTheLineIsNotCutUp() {
    val long = "https://example.com/a/very/long/path/indeed"
    assertEquals(listOf(long), wrap(long, upTo(10)))
  }

  @Test
  fun aWordTooLongDoesNotSwallowTheWordsAfterIt() {
    val lines = wrap("short https://example.com/a/very/long/path next", upTo(12))
    assertTrue(lines.contains("https://example.com/a/very/long/path"))
    assertTrue(lines.any { it.contains("next") })
  }

  @Test
  fun nothingIsLost() {
    val text = "The quick brown fox jumps over the lazy dog, repeatedly and with enthusiasm."
    val words = wrap(text, upTo(14)).joinToString(" ").split(" ").filter { it.isNotEmpty() }
    assertEquals(text.split(" ").filter { it.isNotEmpty() }, words)
  }

  // ---- what the font can write ----

  /**
   * PdfBox refuses the whole document on a character the font cannot encode.
   *
   * Losing a document to a curly quote in a model's output would be absurd, so the common ones
   * are mapped to what Helvetica does have rather than dropped.
   */
  @Test
  fun typographicCharactersBecomeSomethingWritable() {
    assertEquals("'quoted'", writable("‘quoted’"))
    assertEquals("\"quoted\"", writable("“quoted”"))
    assertEquals("a-b", writable("a—b"))
    assertEquals("and so on...", writable("and so on…"))
  }

  @Test
  fun anUnwritableCharacterBecomesAQuestionMarkRatherThanLosingTheDocument() {
    val written = writable("price: 100元")
    assertEquals("price: 100?", written)
    assertFalse(written.contains("元"))
  }

  @Test
  fun ordinaryTextIsUntouched() {
    assertEquals("Ordinary text, 123.", writable("Ordinary text, 123."))
  }

  // ---- never overwriting ----

  @Test
  fun anUnusedNameIsUsedAsIs() {
    assertEquals("Letter.docx", unusedFile(folder.root, "Letter.docx").name)
  }

  /**
   * Asking twice leaves two documents.
   *
   * Replacing the first would be a silent loss of something a model wrote and a person may have
   * kept - and nothing here can know which of the two they wanted.
   */
  @Test
  fun aTakenNameGetsANumberRatherThanReplacingAnything() {
    File(folder.root, "Letter.docx").writeText("first")
    assertEquals("Letter (2).docx", unusedFile(folder.root, "Letter.docx").name)

    File(folder.root, "Letter (2).docx").writeText("second")
    assertEquals("Letter (3).docx", unusedFile(folder.root, "Letter.docx").name)

    // And the original is still there, untouched.
    assertEquals("first", File(folder.root, "Letter.docx").readText())
  }

  @Test
  fun aNameWithNoExtensionStillGetsNumbered() {
    File(folder.root, "Letter").writeText("first")
    assertEquals("Letter (2)", unusedFile(folder.root, "Letter").name)
  }

  @Test
  fun theFileIsAlwaysInsideTheDirectoryItWasGiven() {
    assertEquals(folder.root, unusedFile(folder.root, "x.docx").parentFile)
  }
}
