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

/**
 * A web page reduced to the words on it.
 *
 * Not a parser and not from one. A page handed to a 2B model has to arrive as prose: the
 * markup, the navigation, the cookie banner and the scripts are all tokens that push the
 * actual answer out of a context window that is small to begin with. What is wanted is the
 * text a person would read.
 *
 * The order matters and is the whole design. Script and style content goes first, because
 * stripping tags before removing them would leave the JavaScript behind as "text". Then the
 * page is narrowed to its article if it says where that is. Then block ends become line
 * breaks, so sentences do not run together. Only then are the remaining tags removed.
 */
fun readableText(html: String, limit: Int = LONGEST): String {
  var text = html

  // Comments first: a commented-out block can contain anything, including unbalanced tags
  // that would otherwise confuse every step after this.
  text = COMMENT.replace(text, " ")
  text = NOISE.replace(text, " ")

  // The article, when the page marks one. Most of a news page by weight is navigation and
  // related links, and a model handed all of it answers from the sidebar.
  text = articleOf(text) ?: text

  // Table cells get a divider before the rows become lines. A specification table is most of
  // what a product page is for, and with the cells run together "GPU 1024-core NVIDIA Ampere
  // CPU 6-core Arm" is one sentence nobody can read a figure out of.
  text = CELL_END.replace(text, " | ")
  text = BLOCK_END.replace(text, "\n")
  text = LIST_ITEM.replace(text, "\n• ")
  text = BREAK.replace(text, "\n")
  text = TAG.replace(text, " ")
  text = decodeEntities(text)

  // Whitespace last, because every step above introduces some.
  text =
    text
      .lines()
      .joinToString("\n") { it.replace(SPACES, " ").trim().trimEnd('|', ' ').trimStart('|', ' ') }
      .replace(BLANK_RUN, "\n\n")
      .trim()

  return if (text.length <= limit) text else text.take(limit).substringBeforeLast(' ') + "…"
}

/**
 * The page's own article, if it says where it is.
 *
 * `<article>` first, then `<main>`. Only when what is found is at least an eighth of the
 * page: a site that wraps its navigation in `<main>` and the story in a div would otherwise
 * have Lin read the menu and nothing else, which is worse than reading everything. An eighth
 * is deliberately generous - the cost of keeping the whole page is some wasted tokens, and
 * the cost of narrowing to the wrong element is the answer not being there at all.
 */
private fun articleOf(html: String): String? {
  for (regex in listOf(ARTICLE, MAIN)) {
    val found = regex.find(html)?.groupValues?.getOrNull(1) ?: continue
    if (found.length >= html.length / 8) return found
  }
  return null
}

/**
 * The entities that actually turn up in prose.
 *
 * Not the full set. There are two thousand named entities and a page of text uses eight of
 * them; the rest appear in markup this has already removed. Numeric escapes are handled
 * generally because they are what a CMS emits for anything unusual.
 */
internal fun decodeEntities(text: String): String {
  var out = text
  NAMED.forEach { (name, character) -> out = out.replace(name, character) }
  out =
    NUMERIC.replace(out) { match ->
      val digits = match.groupValues[1]
      val code =
        if (digits.startsWith("x") || digits.startsWith("X")) digits.drop(1).toIntOrNull(16)
        else digits.toIntOrNull()
      // Left as written when it is not a character: a stray "&#" in prose is text, and
      // turning it into a replacement character would lose what was there.
      if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
    }
  // Last, so that "&amp;lt;" decodes to "&lt;" and not to "<" - a page showing example
  // markup should still show it.
  return out.replace("&amp;", "&")
}

/** About as much as a small model can read without the question falling out of its window. */
private const val LONGEST = 12_000

private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val NOISE =
  Regex(
    "<(script|style|noscript|svg|head|nav|footer|form|iframe|template)\\b[^>]*>.*?</\\1>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
  )
private val ARTICLE =
  Regex("<article\\b[^>]*>(.*?)</article>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val MAIN =
  Regex("<main\\b[^>]*>(.*?)</main>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val BLOCK_END =
  Regex("</(p|div|h[1-6]|section|article|tr|ul|ol|blockquote|pre|table)\\s*>", RegexOption.IGNORE_CASE)
private val CELL_END = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
private val LIST_ITEM = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
private val SPACES = Regex("[ \\t\\u00a0]+")
private val BLANK_RUN = Regex("\n{3,}")
private val NUMERIC = Regex("&#(x?[0-9A-Fa-f]+);")

private val NAMED =
  listOf(
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&#39;" to "'",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&hellip;" to "…",
    "&rsquo;" to "'",
    "&lsquo;" to "'",
    "&ldquo;" to "“",
    "&rdquo;" to "”",
    "&trade;" to "™",
    "&copy;" to "©",
    "&reg;" to "®",
    "&deg;" to "°",
    "&pound;" to "£",
    "&euro;" to "€",
  )
