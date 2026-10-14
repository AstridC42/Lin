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

/** What kind of document something is, as far as reading it goes. */
enum class DocKind {
  /** Plain text, including Markdown - read as-is. */
  TEXT,

  /** Word, which is a zip of XML. */
  DOCX,

  PDF,

  /** Something this app cannot read. Named rather than guessed at. */
  UNKNOWN,
}

/**
 * What kind of document a name and a MIME type describe.
 *
 * The name is trusted over the type, which is the opposite of the obvious order and is
 * deliberate. A document arriving through a share or a picker carries whatever MIME type the
 * sending app felt like: `application/octet-stream` is extremely common, and so is
 * `text/plain` on a `.docx`. The extension is the user's own name for the thing. The type is
 * consulted only when the name says nothing.
 */
fun kindOf(name: String, mimeType: String? = null): DocKind {
  val extension = name.substringAfterLast('.', "").lowercase()
  when (extension) {
    "docx" -> return DocKind.DOCX
    "pdf" -> return DocKind.PDF
    "txt",
    "md",
    "markdown",
    "text",
    "log",
    "csv" -> return DocKind.TEXT
    // .doc is not .docx. It is a different, binary format, and reading it as a zip produces
    // nothing - better to say so than to return an empty document.
    "doc" -> return DocKind.UNKNOWN
  }
  return when {
    mimeType == null -> DocKind.UNKNOWN
    mimeType.startsWith("text/") -> DocKind.TEXT
    mimeType == "application/pdf" -> DocKind.PDF
    mimeType.contains("wordprocessingml") -> DocKind.DOCX
    else -> DocKind.UNKNOWN
  }
}

/**
 * The readable text of a Word document's `word/document.xml`.
 *
 * Hand-written rather than an XML parse, and the reason is what the format does. Word splits a
 * single word across several `<w:r>` runs whenever anything changes mid-word - a spell-check
 * mark, a tracked-change boundary, a font switch - so "assistant" can arrive as four runs. Any
 * approach that joins runs with a separator produces "assi st an t". Runs are concatenated with
 * nothing between them; only paragraphs break the line.
 *
 * Three things are deliberately left out:
 *  - `<w:instrText>`, which holds field codes such as a page-reference formula. It is
 *    instructions to Word, not prose, and reads as gibberish in the middle of a sentence.
 *  - `<w:delText>`, which is text someone deleted with tracked changes on. It is still in the
 *    file, and including it would have Lin answer from a draft the author rejected.
 *  - everything in `<w:tbl>` cell borders and style definitions, which carry no text anyway.
 */
fun textFromDocxXml(xml: String): String {
  val out = StringBuilder()
  var index = 0
  while (index < xml.length) {
    val open = xml.indexOf('<', index)
    if (open < 0) break
    val close = xml.indexOf('>', open)
    if (close < 0) break
    val tag = xml.substring(open + 1, close)
    val name = tag.substringBefore(' ').trimEnd('/')

    when {
      // A text run's content, taken verbatim - xml:space is irrelevant here because nothing
      // trims within a run.
      name == "w:t" && !tag.endsWith("/") -> {
        val end = xml.indexOf("</w:t>", close)
        if (end < 0) break
        out.append(unescapeXml(xml.substring(close + 1, end)))
        index = end + "</w:t>".length
        continue
      }
      // Skipped wholesale, content and all. See above for why each one.
      name == "w:instrText" || name == "w:delText" -> {
        val closing = "</$name>"
        val end = xml.indexOf(closing, close)
        index = if (end < 0) xml.length else end + closing.length
        continue
      }
      name == "w:tab" -> out.append('\t')
      name == "w:br" || name == "w:cr" -> out.append('\n')
      // The end of a paragraph is the end of a line. Word has no other notion of one.
      name == "/w:p" -> out.append('\n')
      // An empty paragraph is written self-closing, by Word and by this app's own writer, so it
      // has no closing tag to be counted above. Missing it drops every blank line in the
      // document - which a round-trip through the writer is what caught.
      name == "w:p" && tag.endsWith("/") -> out.append('\n')
    }
    index = close + 1
  }
  return tidy(out.toString())
}

/**
 * XML's five named entities and numeric escapes.
 *
 * Needed because the runs are read out of the raw XML rather than through a parser that would
 * have done this. An unresolved `&amp;` in the middle of a company name is the sort of thing
 * that makes a document look like it was read by a machine.
 */
internal fun unescapeXml(raw: String): String {
  if ('&' !in raw) return raw
  val out = StringBuilder(raw.length)
  var index = 0
  while (index < raw.length) {
    val character = raw[index]
    if (character != '&') {
      out.append(character)
      index++
      continue
    }
    val semicolon = raw.indexOf(';', index)
    if (semicolon < 0 || semicolon - index > MAX_ENTITY) {
      out.append(character)
      index++
      continue
    }
    val entity = raw.substring(index + 1, semicolon)
    val replacement =
      when {
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "quot" -> "\""
        entity == "apos" -> "'"
        entity.startsWith("#x") || entity.startsWith("#X") ->
          entity.drop(2).toIntOrNull(16)?.let { codePointOrNull(it) }
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { codePointOrNull(it) }
        else -> null
      }
    if (replacement == null) {
      // Not an entity this knows. Left exactly as it was rather than dropped: it is more
      // likely to be an ampersand somebody typed than a form this should have handled.
      out.append(character)
      index++
    } else {
      out.append(replacement)
      index = semicolon + 1
    }
  }
  return out.toString()
}

/** A code point as text, or null if it is not one that can be represented. */
private fun codePointOrNull(value: Int): String? =
  if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

/**
 * Document text as something worth sending to a model.
 *
 * Runs of blank lines collapse to one and trailing spaces go. This is not tidiness for its own
 * sake: a Word document with empty styled paragraphs between sections, or a PDF whose layout
 * leaves a space at the end of every line, arrives as mostly whitespace - and on a 2B model with
 * a few thousand tokens of context, whitespace is the thing crowding out the document.
 */
fun tidy(text: String): String =
  text
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .lines()
    .joinToString("\n") { it.trimEnd() }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/**
 * As much of [text] as is worth sending, with a note when it was cut.
 *
 * A document is very often larger than the context of the model reading it, and the failure
 * when it is has a particular shape: the engine rejects the turn for length and the user is
 * told the model is broken rather than that their file is long. Cutting it here is honest and
 * it is said out loud in the text, so the model knows it is working from part of a document
 * and can say so rather than summarising a document it only half received.
 */
fun forReading(text: String, limit: Int = READABLE_CHARACTERS): String {
  if (text.length <= limit) return text
  // Cut at a line rather than mid-word where there is one to cut at.
  val cut = text.lastIndexOf('\n', limit).takeIf { it > limit / 2 } ?: limit
  return text.take(cut).trimEnd() + "\n\n[This is the first part of a longer document.]"
}

/**
 * About two thousand tokens of a document.
 *
 * Was double this, which was too generous and produced the failure it was meant to avoid: on
 * an eight-thousand-token model, a four-thousand-token document plus a system prompt plus the
 * tool schemas plus whatever the conversation already held left nothing, and the engine
 * refused the turn outright. Half of it still answers "what does this say" about anything
 * shorter than a long report, and what is cut is said in the text.
 *
 * Characters rather than tokens because tokenising here would mean asking the engine, and this
 * has to work the same for a document going to a server as for one going to the phone.
 */
private const val READABLE_CHARACTERS = 8_000

/** Longer than any entity this handles, so a bare `&` before a distant `;` is left alone. */
private const val MAX_ENTITY = 10
