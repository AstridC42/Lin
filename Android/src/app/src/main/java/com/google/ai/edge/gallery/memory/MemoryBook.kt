/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.memory

import com.google.ai.edge.gallery.proto.AssistantMemory
import com.google.ai.edge.gallery.proto.RememberedFact
import java.util.UUID

/**
 * What the assistant keeps about the person using it.
 *
 * A conversation that starts from nothing every time is a chatbot. Knowing that "mum" is a
 * particular contact, that the user takes the train, that they are allergic to something - that is
 * the difference between a model on a phone and somebody's assistant.
 *
 * Two forces pull against each other here and both are in these rules.
 *
 * Every fact goes into every system prompt, charged against the same few thousand tokens as the
 * tool schemas and the conversation itself. A memory that grows without limit does not degrade
 * gracefully; it quietly takes the room the conversation needed. So the list is capped, by count
 * and by length, and the cap is enforced here rather than hoped for.
 *
 * And a fact remembered wrongly is worse than one never saved, because it is repeated confidently
 * and never questioned. That is why saving is not silent, why near-duplicates replace rather than
 * accumulate, and why the whole list is shown somewhere it can be edited.
 *
 * Free of Android so all of that can be tested.
 */
object MemoryBook {

  /**
   * How many facts are kept.
   *
   * Twenty short lines is a useful picture of a person and about 250 tokens. Fifty would be a
   * better picture and would cost most of what a small model has left after its tools.
   */
  const val MAX_FACTS = 20

  /** Long enough for a fact, short enough that it cannot become a paragraph. */
  const val MAX_FACT_CHARS = 160

  /**
   * Adds [text], returning the new memory.
   *
   * Replaces an existing fact that says the same thing rather than keeping both: a model asked
   * twice about the same preference will save it twice, and two copies of one fact spend the
   * budget twice to say one thing. "The same thing" is deliberately crude - same text ignoring
   * case, spacing and trailing punctuation - because anything cleverer would start merging facts
   * that differ in a way that matters.
   *
   * Returns null when there is nothing worth saving, so the caller can say so rather than
   * reporting a success that stored an empty string.
   */
  fun remember(
    current: AssistantMemory,
    text: String,
    nowMillis: Long,
    newId: () -> String = { UUID.randomUUID().toString() },
  ): AssistantMemory? {
    val cleaned = text.trim().replace(WHITESPACE, " ").take(MAX_FACT_CHARS)
    if (cleaned.isEmpty()) return null

    val kept = current.factList.filterNot { sameFact(it.text, cleaned) }
    val fact =
      RememberedFact.newBuilder()
        .setId(newId())
        .setText(cleaned)
        .setSavedAtMillis(nowMillis)
        .build()

    // Newest first, and the oldest fall off the end. Dropping the oldest is the least bad rule
    // available without asking: a fact saved long ago and never mentioned since is the most likely
    // to have stopped being true.
    val facts = (listOf(fact) + kept).take(MAX_FACTS)
    return AssistantMemory.newBuilder().addAllFact(facts).build()
  }

  /** Removes the fact with [id], or returns null when there is no such fact. */
  fun forget(current: AssistantMemory, id: String): AssistantMemory? {
    if (current.factList.none { it.id == id }) return null
    return AssistantMemory.newBuilder()
      .addAllFact(current.factList.filterNot { it.id == id })
      .build()
  }

  /**
   * Removes the fact whose text best matches [text], or null when none does.
   *
   * Exists because "forget that I take the train" is how a person asks, and an id is not something
   * they have. Matching is on containment in either direction, and only when exactly one fact
   * matches - two candidates means asking rather than guessing which memory to erase.
   */
  fun forgetMatching(current: AssistantMemory, text: String): AssistantMemory? {
    val needle = normalize(text)
    if (needle.isEmpty()) return null
    val matches =
      current.factList.filter {
        val fact = normalize(it.text)
        fact.contains(needle) || needle.contains(fact)
      }
    if (matches.size != 1) return null
    return forget(current, matches.first().id)
  }

  /**
   * The memory as it goes into the system prompt, or an empty string when there is nothing.
   *
   * Empty matters: the caller leaves the whole section out rather than writing a heading with
   * nothing under it, which would spend tokens telling the model it knows nothing.
   */
  fun format(memory: AssistantMemory): String =
    memory.factList.joinToString("\n") { "- ${it.text}" }

  /** Whether two facts say the same thing, for the crude purposes described in [remember]. */
  private fun sameFact(a: String, b: String): Boolean = normalize(a) == normalize(b)

  private fun normalize(value: String): String =
    value.trim().lowercase().replace(WHITESPACE, " ").trimEnd('.', '!', '?', ',')

  private val WHITESPACE = Regex("\\s+")
}
