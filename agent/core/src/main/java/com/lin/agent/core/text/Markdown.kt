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

package com.lin.agent.core.text

/**
 * Enough Markdown to read a model's reply, and no more.
 *
 * Models write Markdown whether or not anybody asked them to, and until now the screen showed
 * it raw: `**bold**` with the stars, numbered lists running into one paragraph, code and prose
 * in the same typeface. That is the single loudest "unfinished" signal in the app.
 *
 * Not a Markdown library, and not from one. What these models actually emit is headings,
 * bullets, numbers, emphasis, code spans and fenced blocks; tables, footnotes, reference links
 * and HTML do not appear in a two-sentence answer about the weather. A parser for the whole
 * grammar would be more code than the screen that draws it, and every line of it would be
 * reachable only by a model that never writes that way.
 *
 * The rule that shapes the whole thing: **never lose a character**. A marker that does not
 * pair up stays on screen as text. An assistant that quietly swallows half a sentence because
 * it contained a lone asterisk is worse than one that shows the asterisk - the user can read
 * past a stray star, but they cannot read what is not there.
 *
 * It is a counterpart to [com.lin.agent.core.voice.forSpeaking], which strips the same
 * decoration for a speech engine. The two share the awkward case, and for the same reason:
 * an underscore between two alphanumerics is part of `OLLAMA_HOST`, not emphasis.
 */
sealed interface Block {

  /** Ordinary text. */
  data class Paragraph(val spans: List<Span>) : Block

  /** `#`, `##`, `###`. Deeper than three is treated as three; models do not go deeper. */
  data class Heading(val level: Int, val spans: List<Span>) : Block

  /** `- ` or `* `, with [indent] counted in levels of two spaces. */
  data class Bullet(val spans: List<Span>, val indent: Int = 0) : Block

  /** `1. `, keeping the model's own number rather than renumbering it. */
  data class Numbered(val number: Int, val spans: List<Span>) : Block

  /** A fenced block, or a run of indented lines. Kept verbatim, including blank lines. */
  data class Code(val text: String) : Block

  /** `> `. Rare from a model, cheap to support, and ugly when it is not. */
  data class Quote(val spans: List<Span>) : Block
}

/** A run of text with the marks that applied to it. */
data class Span(
  val text: String,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val code: Boolean = false,
)

/** Splits a reply into the blocks a screen can lay out. */
fun markdown(text: String): List<Block> {
  val blocks = mutableListOf<Block>()
  val paragraph = mutableListOf<String>()
  var fenced: MutableList<String>? = null

  fun endParagraph() {
    if (paragraph.isEmpty()) return
    // Joined with spaces: inside a paragraph a newline is a soft wrap, and keeping it would
    // put the model's own line breaks into a column of a different width.
    blocks.add(Block.Paragraph(spansOf(paragraph.joinToString(" "))))
    paragraph.clear()
  }

  for (line in text.lines()) {
    val open = fenced
    if (open != null) {
      // Inside a fence everything is verbatim until the closing one - including the blank
      // lines and the markers that would otherwise be read as bullets.
      if (line.trimStart().startsWith("```")) {
        blocks.add(Block.Code(open.joinToString("\n")))
        fenced = null
      } else {
        open.add(line)
      }
      continue
    }

    val trimmed = line.trim()
    when {
      trimmed.startsWith("```") -> {
        endParagraph()
        fenced = mutableListOf()
      }
      trimmed.isEmpty() -> endParagraph()
      HEADING.matches(trimmed) -> {
        endParagraph()
        val hashes = trimmed.takeWhile { it == '#' }.length
        blocks.add(
          Block.Heading(hashes.coerceAtMost(3), spansOf(trimmed.drop(hashes).trim()))
        )
      }
      BULLET.matches(trimmed) -> {
        endParagraph()
        val indent = (line.length - line.trimStart().length) / 2
        blocks.add(Block.Bullet(spansOf(trimmed.drop(2).trim()), indent = indent))
      }
      NUMBERED.containsMatchIn(trimmed) -> {
        endParagraph()
        val match = NUMBERED.find(trimmed)!!
        blocks.add(
          Block.Numbered(
            number = match.groupValues[1].toIntOrNull() ?: 1,
            spans = spansOf(trimmed.removeRange(match.range).trim()),
          )
        )
      }
      trimmed.startsWith("> ") -> {
        endParagraph()
        blocks.add(Block.Quote(spansOf(trimmed.removePrefix("> ").trim())))
      }
      else -> paragraph.add(trimmed)
    }
  }

  // A fence the model opened and never closed is still the code it was writing. Dropping it
  // would lose the answer; showing it as prose would lose its shape.
  fenced?.let { if (it.isNotEmpty()) blocks.add(Block.Code(it.joinToString("\n"))) }
  endParagraph()
  return blocks
}

/**
 * Splits one line into marked runs.
 *
 * Code first and absolutely: inside backticks nothing else is a marker, because the whole
 * point of showing code is that it is shown as written.
 *
 * An unpaired marker is text. `2 * 3 * 4` is arithmetic, not italics, and a sentence that
 * starts bold and never closes it should still be readable to the end.
 */
fun spansOf(line: String): List<Span> {
  val spans = mutableListOf<Span>()
  val plain = StringBuilder()
  var bold = false
  var italic = false
  var i = 0

  fun flush() {
    if (plain.isEmpty()) return
    spans.add(Span(plain.toString(), bold = bold, italic = italic))
    plain.clear()
  }

  while (i < line.length) {
    val rest = line.substring(i)
    when {
      rest.startsWith("`") -> {
        val close = line.indexOf('`', i + 1)
        if (close < 0) {
          plain.append('`')
          i++
        } else {
          flush()
          spans.add(Span(line.substring(i + 1, close), code = true))
          i = close + 1
        }
      }
      rest.startsWith("**") -> {
        // Already inside: this one closes, and asking for yet another pair would leave the
        // run open to the end of the line. Not inside: it opens only if a closing pair
        // follows and it is not just an asterisk before a space.
        if (bold || opens("**", line, i, 2)) {
          flush()
          bold = !bold
          i += 2
        } else {
          plain.append("**")
          i += 2
        }
      }
      rest.startsWith("*") -> {
        if (italic || opens("*", line, i, 1)) {
          flush()
          italic = !italic
          i++
        } else {
          plain.append('*')
          i++
        }
      }
      // An underscore inside a word belongs to the word: OLLAMA_HOST, a file name, an
      // identifier. Only one at a word edge is emphasis, and only if it closes.
      rest.startsWith("_") && atWordEdge(line, i) -> {
        if (italic || (closesUnderscore(line, i + 1) && notSpace(line, i + 1))) {
          flush()
          italic = !italic
          i++
        } else {
          plain.append('_')
          i++
        }
      }
      else -> {
        plain.append(line[i])
        i++
      }
    }
  }
  flush()
  return spans
}

/**
 * Whether a marker at [at] opens emphasis.
 *
 * Two conditions, and the second is the one that matters: the marker must be followed by
 * something other than a space. Without it, `2 * 3 * 4 = 24` is arithmetic read as italics -
 * which is how a model's answer about multiplication came out slanted and missing its stars.
 */
private fun opens(marker: String, line: String, at: Int, width: Int): Boolean {
  val after = at + width
  return notSpace(line, after) && line.indexOf(marker, after) >= 0
}

private fun notSpace(line: String, at: Int): Boolean {
  val c = line.getOrNull(at) ?: return false
  return !c.isWhitespace()
}

private fun closesUnderscore(line: String, from: Int): Boolean {
  var at = line.indexOf('_', from)
  while (at >= 0) {
    if (atWordEdge(line, at)) return true
    at = line.indexOf('_', at + 1)
  }
  return false
}

/** True when the character is not wedged between two alphanumerics. */
private fun atWordEdge(line: String, at: Int): Boolean {
  val before = line.getOrNull(at - 1)
  val after = line.getOrNull(at + 1)
  return !(before != null && before.isLetterOrDigit() && after != null && after.isLetterOrDigit())
}

private val HEADING = Regex("^#{1,6} .*")
private val BULLET = Regex("^[-*] .*")
private val NUMBERED = Regex("^(\\d{1,3})[.)] ")
