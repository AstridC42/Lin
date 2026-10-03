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

package com.google.ai.edge.gallery.voice

/**
 * Turning the text of whatever app is on screen into something worth asking a model about.
 *
 * The platform hands an assistant the view hierarchy of the app in front, and taken literally that
 * is hundreds of nodes: every label, every button, the same string repeated once per list row that
 * happens to be recycled, and a great deal of furniture nobody would read aloud. Sent as-is it
 * would be several thousand tokens of mostly nothing, on a budget already shared between the
 * tools, what is remembered and the conversation.
 *
 * So it is flattened, de-duplicated and capped, in that order, and the cap is enforced here rather
 * than hoped for. Free of Android so the ordering and the limits can be tested - the tree walk
 * that produces the lines lives with the session.
 */
object ScreenText {

  /**
   * How much screen text is worth sending.
   *
   * Roughly 500 tokens: enough for an article's opening, a receipt, a message thread, which is
   * what people actually ask about. More than that and the answer starts being limited by having
   * no room left to think in.
   */
  const val MAX_CHARS = 2_000

  /** Below this, a line is furniture: "OK", "1", an icon's label. */
  private const val MIN_LINE_CHARS = 2

  /**
   * The screen's text, in reading order, or an empty string when there is nothing worth sending.
   *
   * De-duplication is case-sensitive and whole-line. A recycled list shows the same label many
   * times and the model should see it once; two lines that differ only in case are usually two
   * genuinely different pieces of text, so those are kept apart.
   */
  fun summarize(lines: List<String>, maxChars: Int = MAX_CHARS): String {
    val seen = LinkedHashSet<String>()
    for (raw in lines) {
      val line = raw.trim().replace(WHITESPACE, " ")
      if (line.length < MIN_LINE_CHARS) continue
      seen.add(line)
    }
    if (seen.isEmpty()) return ""

    // Truncated at a line boundary rather than mid-word: a sentence cut in half is worse than one
    // line fewer, because the model will answer about the half it was given.
    val builder = StringBuilder()
    for (line in seen) {
      if (builder.isEmpty()) {
        // The first line goes in even if it alone is over the cap, so a single long paragraph
        // still produces something rather than nothing.
        builder.append(line.take(maxChars))
        continue
      }
      if (builder.length + 1 + line.length > maxChars) break
      builder.append('\n').append(line)
    }
    return builder.toString()
  }

  private val WHITESPACE = Regex("\\s+")
}
