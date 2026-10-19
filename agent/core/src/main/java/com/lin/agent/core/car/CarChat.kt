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

package com.lin.agent.core.car

import com.lin.agent.core.history.SavedChat
import com.lin.agent.core.text.splitSources
import com.lin.agent.core.training.TrainingData
import com.lin.agent.core.voice.forSpeaking

/**
 * Lin as a conversation in Android Auto.
 *
 * Android Auto does not let another app take its microphone button, and it draws only its own
 * screens - but it does carry messaging apps: it announces a new message, reads it aloud, and
 * lets the driver answer by voice. So Lin talks to the car as a messaging app would. Its answer
 * arrives as a message, the car reads it out, the driver replies, and the reply comes back to
 * Lin as text. That is the one hands-free loop in Android Auto open to an app like this one.
 */

/** Conversations kept for the car carry this prefix, so the history drawer shows them too. */
const val CAR_CHAT_PREFIX = "car-"

/**
 * What the model is told on top of everything else, because what it writes is heard, not read,
 * by somebody driving.
 */
const val CAR_CHAT_PROMPT =
  "The person is driving. Your answer is read aloud to them by the car, so answer in one to " +
    "three short spoken sentences: no lists, tables, links, headings or markdown, and no " +
    "follow-up questions unless you cannot act without one. "

/**
 * An answer as the car should say it: no source list, no [1] citations, no addresses, no
 * markdown, and short enough to listen to.
 *
 * The full answer is still kept in the history; this is only what goes to the car.
 */
fun forTheCar(answer: String, longest: Int = 600): String {
  val body =
    splitSources(answer)
      .body
      .replace(MARKDOWN_LINK, "$1")
      .replace(CITATION, "")
      .replace(ADDRESS, "")
  val said = forSpeaking(body).replace(SPACES, " ").replace(" .", ".").trim()
  if (said.length <= longest) return said
  // At the end of a sentence if there is one in the second half, so it does not stop mid-thought.
  val head = said.take(longest)
  val sentence = head.lastIndexOfAny(charArrayOf('.', '!', '?'))
  if (sentence >= longest / 2) return head.take(sentence + 1)
  return head.substringBeforeLast(' ').ifEmpty { head } + "…"
}

/**
 * The car conversation a reply continues: the most recent one, if it was used in the last half
 * hour. Otherwise a new one begins - the same boundary the phone's conversations use.
 */
fun carChatToContinue(chats: List<SavedChat>, now: Long): SavedChat? =
  chats
    .filter { it.id.startsWith(CAR_CHAT_PREFIX) }
    .maxByOrNull { it.updatedAt }
    ?.takeUnless { TrainingData.startsNewConversation(it.updatedAt, now) }

private val MARKDOWN_LINK = Regex("\\[([^\\]]+)]\\((?:https?://)[^)]*\\)")
private val CITATION = Regex("\\s?\\[\\d+(?:\\s*,\\s*\\d+)*]")
private val ADDRESS = Regex("\\(?https?://\\S+\\)?")
private val SPACES = Regex("\\s+")
