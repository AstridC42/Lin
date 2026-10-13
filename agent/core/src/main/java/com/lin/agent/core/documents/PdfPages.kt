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

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.OutputStream

/**
 * Lays prose out onto PDF pages.
 *
 * A PDF has no notion of a paragraph that flows - every line is placed at a coordinate - so
 * this is a typesetter, however small. It wraps to the page width, breaks pages at the bottom
 * margin, and does nothing else: a model's prose gets one font at one size, which is the honest
 * output for text that arrived as text.
 *
 * Only Helvetica, which is one of the fourteen fonts every PDF reader is required to have. That
 * keeps the file small and means it needs no embedded font - at the cost of being able to write
 * only the Latin range, which is said plainly rather than silently dropping characters.
 */
class PdfPages(private val context: Context) : PdfWriter {

  override fun write(text: String, out: OutputStream) {
    PDFBoxResourceLoader.init(context)
    PDDocument().use { document ->
      val font = PDType1Font.HELVETICA
      val lines = wrap(tidy(text), font.widthLimit())
      var remaining = lines
      while (true) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val fit = (page.mediaBox.height - 2 * MARGIN) / LEADING
        val onThisPage = remaining.take(fit.toInt())
        PDPageContentStream(document, page).use { stream ->
          stream.beginText()
          stream.setFont(font, FONT_SIZE)
          stream.setLeading(LEADING.toDouble())
          stream.newLineAtOffset(MARGIN, page.mediaBox.height - MARGIN)
          onThisPage.forEach { line ->
            // Written one line at a time with an explicit newline, because showText rejects a
            // string containing one.
            stream.showText(line)
            stream.newLine()
          }
          stream.endText()
        }
        remaining = remaining.drop(onThisPage.size)
        if (remaining.isEmpty()) break
      }
      document.save(out)
    }
  }

  private fun PDType1Font.widthLimit(): (String) -> Boolean = { candidate ->
    // getStringWidth is in thousandths of the font size.
    getStringWidth(candidate) / 1000f * FONT_SIZE <= PDRectangle.A4.width - 2 * MARGIN
  }

  private companion object {
    const val FONT_SIZE = 11f
    const val LEADING = 15f
    const val MARGIN = 56f
  }
}

/**
 * [text] broken into lines that fit, keeping its blank lines.
 *
 * A word longer than a whole line - a URL, usually - is placed on its own line and allowed to
 * run over rather than being cut up. Hyphenating a link silently breaks it, and a visibly long
 * line is the lesser fault.
 *
 * Characters outside what the font can write are replaced rather than dropped, because
 * PdfBox refuses the whole document on one of them - and losing a document to a stray curly
 * quote in a model's output would be absurd.
 */
internal fun wrap(text: String, fits: (String) -> Boolean): List<String> {
  val out = mutableListOf<String>()
  for (paragraph in text.lines()) {
    val safe = writable(paragraph)
    if (safe.isBlank()) {
      out.add("")
      continue
    }
    var line = StringBuilder()
    for (word in safe.split(' ').filter { it.isNotEmpty() }) {
      val candidate = if (line.isEmpty()) word else "$line $word"
      when {
        fits(candidate) -> line = StringBuilder(candidate)
        line.isEmpty() -> out.add(word)
        else -> {
          out.add(line.toString())
          line = StringBuilder(word)
        }
      }
    }
    if (line.isNotEmpty()) out.add(line.toString())
  }
  return out
}

/** [text] with everything Helvetica cannot write turned into something it can. */
internal fun writable(text: String): String =
  buildString(text.length) {
    for (character in text) {
      when (character) {
        '‘',
        '’',
        '‛' -> append('\'')
        '“',
        '”' -> append('"')
        '–',
        '—',
        '−' -> append('-')
        '…' -> append("...")
        ' ',
        ' ',
        ' ',
        ' ' -> append(' ')
        '\t' -> append("    ")
        else -> if (character.code in 0x20..0xFF) append(character) else append('?')
      }
    }
  }
