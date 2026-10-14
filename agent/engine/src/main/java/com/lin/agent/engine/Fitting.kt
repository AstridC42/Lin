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

package com.lin.agent.engine

/**
 * Roughly how many tokens a piece of text is.
 *
 * Four characters to a token, which is the usual approximation for English and wrong in both
 * directions for everything else. That is acceptable here and would not be if this were
 * billing somebody: the number is used to decide what to drop, and dropping one message too
 * many costs a little memory of the conversation while dropping one too few costs the whole
 * turn.
 */
internal fun tokensOf(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

/**
 * What one picture costs.
 *
 * A vision encoder turns an image into a fixed block of tokens regardless of its size, so this
 * is a constant rather than something derived from the bytes. Deliberately on the high side:
 * under-counting an image is how a conversation that looks like it fits does not.
 */
internal const val TOKENS_PER_PICTURE = 300

private const val CHARS_PER_TOKEN = 4

/**
 * As much of [messages] as will fit in [contextTokens], newest first.
 *
 * Nothing used the context window before this. `BackendCapabilities.contextWindow` was
 * declared, carried around and never read, so a conversation grew until the engine refused it -
 * which is the failure this project's own notes blame for the fork becoming unworkable
 * (`Input token ids are too long: 4230 >= 4000`). Pictures in the history brought it closer.
 *
 * Three rules, in order of how much they cost:
 *
 *  1. The last message is always kept, whatever it costs. It is the question being asked, and a
 *     turn without it is not a shorter conversation but a different one.
 *  2. Pictures are dropped from older messages before the messages themselves. A picture is
 *     worth a few hundred tokens and is almost never what a later question is about, so this
 *     buys the most history back per token.
 *  3. Then whole messages go, oldest first.
 *
 * And a tool result left at the front is dropped: an answer with no visible question reads as
 * something the user said, which is worse than the gap it fills.
 */
fun fitToContext(messages: List<Message>, contextTokens: Int, reserve: Int = RESERVE): List<Message> {
  if (messages.isEmpty()) return messages
  // Zero means the backend did not say - a server decides its own window, and guessing one
  // here would trim conversations that were fine.
  if (contextTokens <= 0) return messages

  val budget = contextTokens - reserve
  if (budget <= 0) return listOf(messages.last())

  val last = messages.last()
  var spent = costOf(last)
  val kept = ArrayDeque<Message>()
  kept.addFirst(last)

  // Older pictures go first, which is cheaper than losing what was said.
  for (index in messages.size - 2 downTo 0) {
    val message = messages[index]
    val withoutPictures =
      if (message.attachments.isEmpty()) message
      else message.copy(attachments = message.attachments.filterNot { it is Attachment.Picture })
    val cost = costOf(withoutPictures)
    if (spent + cost > budget) break
    spent += cost
    kept.addFirst(withoutPictures)
  }

  while (kept.size > 1 && kept.first().role == Message.Role.TOOL) kept.removeFirst()
  return kept.toList()
}

/** What a message costs: its words, plus a fixed block for each picture on it. */
private fun costOf(message: Message): Int =
  tokensOf(message.text) +
    message.attachments.count { it is Attachment.Picture } * TOKENS_PER_PICTURE

/**
 * Room left for the system prompt, the tool schemas and the reply itself.
 *
 * Generous on purpose. The tool list alone is several hundred tokens, and running out while
 * generating is a reply that stops mid-sentence - which is worse than a conversation that
 * remembers slightly less.
 */
private const val RESERVE = 1500
