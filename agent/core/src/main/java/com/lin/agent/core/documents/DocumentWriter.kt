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
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a document can be written as. */
enum class DocFormat(val extension: String, val mimeType: String) {
  TXT("txt", "text/plain"),
  MARKDOWN("md", "text/markdown"),
  DOCX(
    "docx",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ),
  PDF("pdf", "application/pdf"),
}

/** The format a requested name or format word asks for, defaulting to [DocFormat.DOCX]. */
fun formatFor(requested: String?, name: String = ""): DocFormat {
  val fromName = name.substringAfterLast('.', "").lowercase()
  val asked = requested?.trim()?.lowercase()?.removePrefix(".").orEmpty()
  for (candidate in listOf(asked, fromName)) {
    when (candidate) {
      "txt",
      "text",
      "plain" -> return DocFormat.TXT
      "md",
      "markdown" -> return DocFormat.MARKDOWN
      "pdf" -> return DocFormat.PDF
      "docx",
      "doc",
      "word" -> return DocFormat.DOCX
    }
  }
  // Word, because it is the one a person can open and then edit. A PDF they cannot.
  return DocFormat.DOCX
}

/**
 * A file name that is safe to create and recognisable as what was asked for.
 *
 * The name comes from a model, which means it can contain a path, a newline, or three hundred
 * characters. Separators are the part that matters: "../../shared_prefs/x" written as given
 * would leave Lin's own directory, so every separator goes rather than being escaped.
 */
fun fileNameFor(requested: String, format: DocFormat): String {
  val base =
    requested
      .substringAfterLast('/')
      .substringAfterLast('\\')
      .substringBeforeLast('.')
      .map { if (it.isLetterOrDigit() || it in " -_()&,.") it else ' ' }
      .joinToString("")
      .replace(Regex(" {2,}"), " ")
      .trim()
      .take(MAX_NAME)
      // Dots as well as spaces: a requested name of ".." survives everything above as "." and
      // would be written as the hidden file "..docx".
      .trim { it.isWhitespace() || it == '.' }
      .ifEmpty { "Document" }
  return "$base.${format.extension}"
}

/**
 * Writes [text] into [out] in [format].
 *
 * Each format is built here rather than through a document model, because what is being written
 * is always prose that a model produced: paragraphs, and nothing else. A writer that supported
 * styles would be a writer whose output depends on a 2B model's idea of styling.
 */
fun write(text: String, format: DocFormat, out: OutputStream, pdf: PdfWriter? = null) {
  when (format) {
    DocFormat.TXT,
    DocFormat.MARKDOWN -> out.write(text.toByteArray())
    DocFormat.DOCX -> writeDocx(text, out)
    DocFormat.PDF ->
      requireNotNull(pdf) { "A PDF needs a writer with a font behind it" }.write(text, out)
  }
}

/** Writing a PDF needs fonts, which need a platform. Kept behind this so the rest stays pure. */
fun interface PdfWriter {
  fun write(text: String, out: OutputStream)
}

/**
 * The smallest zip Word will open: a content-type map, a relationship to the body, and the body.
 *
 * Three entries rather than the dozen Word itself writes. Everything else in a real .docx -
 * styles, fonts, settings, theme, app properties - is optional, and each one is another file
 * whose schema could be subtly wrong. What is here is what the format requires.
 */
internal fun writeDocx(text: String, out: OutputStream) {
  ZipOutputStream(out).use { zip ->
    zip.putNextEntry(ZipEntry("[Content_Types].xml"))
    zip.write(CONTENT_TYPES.toByteArray())
    zip.closeEntry()

    zip.putNextEntry(ZipEntry("_rels/.rels"))
    zip.write(RELATIONSHIPS.toByteArray())
    zip.closeEntry()

    zip.putNextEntry(ZipEntry("word/document.xml"))
    zip.write(docxBody(text).toByteArray())
    zip.closeEntry()
  }
}

/**
 * The body XML for [text], one paragraph per line.
 *
 * `xml:space="preserve"` on every run, because without it Word is entitled to drop the leading
 * and trailing spaces of each one - which eats the space in "the end. Next" when a line happens
 * to start with one.
 */
internal fun docxBody(text: String): String {
  val paragraphs =
    tidy(text).lines().joinToString("") { line ->
      if (line.isEmpty()) "<w:p/>"
      else """<w:p><w:r><w:t xml:space="preserve">${escapeXml(line)}</w:t></w:r></w:p>"""
    }
  return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
    "<w:body>$paragraphs</w:body></w:document>"
}

/**
 * XML-escapes [raw], including the characters XML simply cannot carry.
 *
 * Control characters are dropped rather than escaped. XML 1.0 has no way to represent most of
 * them even as numeric references, so a document containing one would be rejected by Word as
 * corrupt - and a stray 0x0C from a model's output is not worth losing a document over.
 */
internal fun escapeXml(raw: String): String =
  buildString(raw.length) {
    for (character in raw) {
      when {
        character == '&' -> append("&amp;")
        character == '<' -> append("&lt;")
        character == '>' -> append("&gt;")
        character == '"' -> append("&quot;")
        character == '\'' -> append("&apos;")
        character == '\t' || character == '\n' -> append(character)
        character.code < 0x20 -> Unit
        else -> append(character)
      }
    }
  }

private const val MAX_NAME = 80

private const val CONTENT_TYPES =
  """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
    """<Default Extension="xml" ContentType="application/xml"/>""" +
    """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
    """</Types>"""

private const val RELATIONSHIPS =
  """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
    """<Relationship Id="rId1" Target="word/document.xml" """ +
    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>""" +
    """</Relationships>"""

/**
 * [name] in [directory], or the next free "name (2)" if it is taken.
 *
 * Never overwrites, not even something Lin wrote itself. Asking twice for a letter should leave
 * two letters to choose between rather than silently replacing the first - the model has no way
 * to know which one the user kept.
 */
fun unusedFile(directory: File, name: String): File {
  val base = name.substringBeforeLast('.')
  val extension = name.substringAfterLast('.', "")
  val suffix = if (extension.isEmpty()) "" else ".$extension"
  var candidate = File(directory, name)
  var counter = 2
  while (candidate.exists() && counter < MAX_TRIES) {
    candidate = File(directory, "$base ($counter)$suffix")
    counter++
  }
  return candidate
}

private const val MAX_TRIES = 100
