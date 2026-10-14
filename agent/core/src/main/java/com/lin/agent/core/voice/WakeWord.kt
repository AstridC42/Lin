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

package com.lin.agent.core.voice

/**
 * Hearing "Hey Lin" in what the recogniser thought it heard.
 *
 * This is a phrase matcher over transcripts rather than a wake-word model, and the difference
 * is worth being plain about. A real wake-word engine listens to audio at almost no cost; this
 * runs the recogniser continuously and reads the words, which is why the setting warns about
 * the battery and is off until somebody turns it on. No pre-trained model exists for "Hey
 * Lin" - the open ones ship "Hey Jarvis" and "Okay Nabu", and a custom one means somebody's
 * cloud console and an account - so this is what can be built today without either.
 *
 * The matching is where the care goes. A recogniser hands back "hey Lynn", "hey line", "hey
 * len" and "Hey, Lin!" for the same two syllables, and a matcher that insists on the spelling
 * never fires. One that is too loose fires at "feeling" and opens the microphone in somebody's
 * pocket.
 */
object WakeWord {

  /** What the user says. Not configurable: a tested phrase is worth more than a choice here. */
  const val PHRASE = "Hey Lin"

  /**
   * Whether [heard] contains the wake phrase.
   *
   * Both halves, adjacent, each allowed to be one character wrong. Requiring both is what
   * stops "Lynn" in ordinary speech from waking it; allowing a character of slack in each is
   * what makes it fire at all, given what a recogniser does with a two-syllable name.
   */
  fun saidIn(heard: String): Boolean {
    val words = heard.lowercase().split(NOT_A_LETTER).filter { it.isNotEmpty() }
    for (index in 0 until words.size - 1) {
      if (isHey(words[index]) && isLin(words[index + 1])) return true
    }
    // Said as one word, which is what a recogniser does with a phrase it half-knows.
    return words.any { it.length in 5..7 && isHey(it.take(3)) && isLin(it.drop(3)) }
  }

  /**
   * What comes after the wake phrase, or empty if nothing did.
   *
   * "Hey Lin, what's the time" should ask the question rather than only opening the
   * microphone and waiting for it to be said again.
   */
  fun afterWake(heard: String): String {
    val words = heard.split(NOT_A_LETTER_KEEPING_SPACE).filter { it.isNotBlank() }
    for (index in 0 until words.size - 1) {
      if (isHey(words[index].lowercase()) && isLin(words[index + 1].lowercase())) {
        return words.drop(index + 2).joinToString(" ").trim()
      }
    }
    return ""
  }

  private fun isHey(word: String): Boolean = word == "hey" || nearly(word, "hey")

  /**
   * "Lin", and the handful of things a recogniser hears instead.
   *
   * Listed as well as fuzzy-matched because the common mishearings are not all one character
   * away: "lynn" is, "line" is, "len" is, but a recogniser that offers "linn" or "lyn" should
   * also count and an edit distance of one would miss none of those - the list is for
   * readability and the distance is what does the work.
   */
  private fun isLin(word: String): Boolean =
    word in setOf("lin", "lynn", "lyn", "linn", "line", "len") || nearly(word, "lin")

  /**
   * One substitution or insertion apart. Never a deletion, and that is the point.
   *
   * A deletion lets a shorter word through, and short words are where the false wakes live:
   * "li" is one deletion from "lin" and "he" is one from "hey", so "he li" - two ordinary
   * syllables - would open the microphone. Requiring at least the target's length costs the
   * mishearings that drop a letter, which a recogniser rarely produces, and buys back the
   * whole class of accidental wakes.
   */
  private fun nearly(word: String, target: String): Boolean {
    if (word.length < target.length || word.length > target.length + 1) return false
    if (word == target) return true

    var differences = 0
    var i = 0
    var j = 0
    while (i < word.length && j < target.length) {
      if (word[i] == target[j]) {
        i++
        j++
        continue
      }
      if (++differences > 1) return false
      when {
        word.length > target.length -> i++
        word.length < target.length -> j++
        else -> {
          i++
          j++
        }
      }
    }
    return differences + (word.length - i) + (target.length - j) <= 1
  }

  private val NOT_A_LETTER = Regex("[^a-z0-9]+")

  private val NOT_A_LETTER_KEEPING_SPACE = Regex("[^A-Za-z0-9' ]+|\\s+")
}
