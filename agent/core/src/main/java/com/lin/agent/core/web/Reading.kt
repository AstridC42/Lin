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

import java.util.Locale

/**
 * The parts of a page that answer the question, within a budget.
 *
 * Why not just the top of the page: a product page opens with a slogan and a hero image's alt
 * text, and the specification table somebody asked about is two thousand words further down.
 * Handing a model the first few thousand characters handed it the marketing, and it answered
 * from that - which is the complaint this exists for: "it only used the titles".
 *
 * Why not only the lines that mention the question: the rows of a specification table do not.
 * "AI Performance | 67 TOPS" contains neither "Orin" nor "Nano". What does mention them is the
 * heading above the table - "Jetson Orin Nano Super Developer Kit technical specifications" -
 * so a chunk that matches brings the chunk after it along. That one rule is what gets the
 * table in.
 *
 * The opening is kept too when there is room, because it says what the page is about, and an
 * answer that cannot say which product a figure belongs to is not much of an answer.
 */
fun relevantPassages(text: String, query: String, budget: Int = 2_500): String {
  val clean = text.trim()
  if (clean.length <= budget) return clean
  val terms = termsOf(query)
  val lines = withoutMenus(clean.lines().map { it.trim() })
  if (terms.isEmpty() || lines.isEmpty()) return cut(clean, budget)

  val scores = lines.map { line -> terms.count { line.lowercase(Locale.ROOT).contains(it) } }
  if (scores.all { it == 0 }) return cut(clean, budget)

  val chosen = sortedSetOf<Int>()
  var used = 0
  // Lines, whole, never cut: a table row cut in half is a figure with no label, which is
  // worse than no figure.
  fun take(index: Int): Boolean {
    if (index !in lines.indices || index in chosen) return true
    val size = lines[index].length + 1
    if (used + size > budget) return false
    chosen.add(index)
    used += size
    return true
  }

  // The opening, as far as a fifth of the budget, so the answer can say what the page is.
  var opening = 0
  for (index in lines.indices) {
    opening += lines[index].length + 1
    if (opening > budget / 5) break
    take(index)
  }

  // Best first; position breaks ties so that an earlier mention wins. Each match brings the
  // lines after it, up to a short window - the heading brings its table.
  val order = lines.indices.filter { scores[it] > 0 }.sortedWith(compareBy({ -scores[it] }, { it }))
  outer@ for (index in order) {
    if (!take(index)) break
    var window = 0
    var next = index + 1
    while (next < lines.size && window < FOLLOWING) {
      window += lines[next].length + 1
      if (!take(next)) break@outer
      next++
    }
  }

  return buildString {
    var last = -2
    for (index in chosen) {
      if (isNotEmpty()) append(if (index == last + 1) "\n" else "\n…\n")
      append(lines[index])
      last = index
    }
  }
}

/**
 * How much follows a matching line: a real specification table's worth.
 *
 * NVIDIA's runs to twenty-odd rows. The window cannot know where a table ends, so a line or
 * two of whatever comes after can ride along at its edge - the price of never cutting a long
 * table short, and a much smaller one than losing the last rows.
 */
private const val FOLLOWING = 1_000

/**
 * The page's lines without the menu residue.
 *
 * Seen on NVIDIA's own product page: the section menu - "• Introduction • Highlights •
 * Workloads…" - appears twice, once per screen size, and arrives as forty lines of single
 * words and bullets at the very top, where the opening is taken from. A bullet with nothing
 * after it is always residue. A short line that has already appeared once is a menu entry the
 * second time; long lines and table rows are left alone, because a repeated figure can be the
 * point.
 */
internal fun withoutMenus(lines: List<String>): List<String> {
  val seen = mutableSetOf<String>()
  return lines.filter { line ->
    when {
      line.isEmpty() -> false
      line.trim('•', ' ', '·', '|').isEmpty() -> false
      line.length < SHORT_LINE && !line.contains(" | ") -> seen.add(line.lowercase(Locale.ROOT))
      else -> true
    }
  }
}

private const val SHORT_LINE = 30

/** The words of a question worth matching on: lower case, no filler, nothing too short. */
internal fun termsOf(query: String): List<String> =
  query
    .lowercase(Locale.ROOT)
    .split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.length >= 2 && it !in FILLER && !it.matches(Regex("(19|20)\\d{2}")) }
    .distinct()

private fun cut(text: String, budget: Int): String =
  if (text.length <= budget) text else text.take(budget).substringBeforeLast(' ') + "…"

/** One search result, and what Lin managed to read of it - empty if it was not read. */
data class ReadHit(val hit: SearchHit, val read: String)

/**
 * The search, as the model is handed it: each source numbered, with what the page actually
 * says under it, and instructions at the end where a small model will still see them.
 *
 * The instructions are the point. The complaint was an answer built from titles, with no
 * links - so the result says, in as many words, to answer from the page text, to give the
 * figures as the page gives them, and to finish with the links it used. Numbered here so the
 * model can cite "[2]" and the list at the end matches.
 */
fun searchBriefing(query: String, hits: List<ReadHit>, source: String = ""): String {
  if (hits.isEmpty()) return "Nothing came back for \"$query\"."
  val anyRead = hits.any { it.read.isNotBlank() }
  return buildString {
    append("Results for \"").append(query).append("\"")
    if (source.isNotBlank()) append(" (").append(source.trim()).append(")")
    append(":")
    hits.forEachIndexed { index, each ->
      append("\n\n[").append(index + 1).append("] ").append(each.hit.title.ifBlank { each.hit.url })
      if (each.hit.url.isNotEmpty()) append("\n").append(each.hit.url)
      if (each.read.isNotBlank()) {
        append("\nWhat the page says:\n").append(each.read.trim())
      } else if (each.hit.snippet.isNotBlank()) {
        append("\nSnippet only (not read): ").append(each.hit.snippet.trim())
      }
    }
    append("\n\n")
    if (anyRead) {
      append(
        "Answer from what the pages say, not from their titles. Give figures and names exactly " +
          "as the page gives them, and say which source each came from, like [1]. "
      )
    } else {
      append(
        "None of these pages could be read, only their snippets - say so, and offer to open " +
          "one with read_page. "
      )
    }
    append(
      "End your answer with a \"Sources:\" list of the links you actually used, one per line, " +
        "numbered as above. Never drop the links."
    )
  }
}

/**
 * Everything a reader has, up to [limit] characters.
 *
 * In a loop, and the loop is the whole function. This used to be one `read(buffer, 0, limit)`,
 * which reads as "up to the limit" and means "whatever is buffered right now" - on a network
 * stream, the first few kilobytes. So every page Lin read was its first chunk: for Wikipedia
 * that is the `<head>`, which arrived on screen as "Tegra - Wikipedia" followed by half a
 * `<link` tag, and for a SearXNG with results it was the first part of a JSON document, which
 * fails to parse and came out as "no results". The only answers that survived intact were the
 * short ones - which is to say the failures. Three days of chasing a search fault were spent
 * downstream of this.
 */
fun readAtMost(reader: java.io.Reader, limit: Int): String {
  val out = StringBuilder()
  val buffer = CharArray(8_192)
  while (out.length < limit) {
    val read = reader.read(buffer, 0, minOf(buffer.size, limit - out.length))
    if (read < 0) break
    out.appendRange(buffer, 0, read)
  }
  return out.toString()
}

/**
 * Whether a search came back with nothing it had not already returned.
 *
 * The loop this ends: asked for specifications, Lin searched six times in a row -
 * "specifications", "technical specifications", "specifications table", "official specs
 * datasheet" - and got the same three pages every time, because the words changed and the
 * index did not. A small model does not notice it is going round; the result has to say so.
 *
 * Every address must have been seen before, and in a single earlier result. A new page among
 * old ones is progress and is not stopped.
 */
fun samePagesAgain(urls: List<String>, earlier: List<Set<String>>): Boolean =
  urls.isNotEmpty() && earlier.any { it.containsAll(urls) }

/** What the model is told when it is searching round in a circle. */
fun goingRoundText(query: String, titles: List<String>): String =
  "\"$query\" returned the same pages you were already given: ${titles.joinToString("; ")}. " +
    "Searching again with different words will not find anything new. Do not search again " +
    "for this. Answer from what those pages said, and if that does not answer the question, " +
    "tell the user plainly what you found and what you could not find."
