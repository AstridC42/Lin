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

package com.lin.agent.core.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading a finished conversation for the handful of things worth keeping.
 *
 * The part of memory held back until there was a screen to see it on, because a digest decides
 * on somebody's behalf what about their day is worth writing down - and being wrong about that
 * is a privacy problem rather than a bug.
 *
 * The quality risk is specific and this file is mostly about it. Ask a 2B model what is worth
 * remembering from a conversation and it will happily answer "the user said hello" and "the
 * user asked about the weather". Those are facts about the conversation, not about the person,
 * and a memory store full of them is worse than an empty one: it crowds out the real entries in
 * every search and makes the screen unreadable. So the schema is narrow, the prompt says what
 * not to produce, and everything that comes back is filtered again here - because a prompt is a
 * request and a filter is a rule.
 */
object Digest {

  /** Nothing is kept from a conversation shorter than this. Two turns is not a day. */
  const val MIN_MESSAGES = 4

  /** A ceiling per conversation, so one long evening cannot flood the store. */
  const val MAX_FACTS = 5

  /**
   * Close enough to something already remembered to be the same fact.
   *
   * High on purpose. Below this, two genuinely different facts about the same subject - a
   * sister's birthday and a sister's name - start suppressing each other, and losing a real
   * memory to deduplication is worse than holding a near-duplicate the user can delete.
   */
  const val SAME_FACT = 0.92f

  /** What the model is asked to produce. */
  val SCHEMA: String =
    JSONObject()
      .put("type", "object")
      .put(
        "properties",
        JSONObject()
          .put(
            "facts",
            JSONObject()
              .put("type", "array")
              .put("items", JSONObject().put("type", "string"))
              .put(
                "description",
                "Lasting facts about the person: names, relationships, preferences, " +
                  "commitments, things they asked to be remembered. Empty if there are none.",
              ),
          ),
      )
      .put("required", JSONArray().put("facts"))
      .toString()

  /**
   * What the model is told. Short, and mostly about what not to write.
   *
   * The negative instructions earn their space: left to itself the model summarises the
   * conversation, which is exactly the thing that must not be stored.
   */
  val PROMPT: String =
    "Read this conversation and list only lasting facts about the person - their name, people " +
      "in their life, preferences, commitments, anything they asked to be remembered. " +
      "Do not record what was discussed, what they asked, or what you did. " +
      "Do not record anything that is only true today. " +
      "Write each as one short sentence that will still make sense in six months with no " +
      "conversation around it. If there is nothing lasting, return an empty list."

  /** Reads the model's answer. Anything unparseable yields nothing rather than a guess. */
  fun parse(json: String): List<String> {
    val facts =
      runCatching { JSONObject(json).optJSONArray("facts") }.getOrNull() ?: return emptyList()
    return (0 until facts.length())
      .mapNotNull { facts.optString(it).trim().takeIf(String::isNotEmpty) }
      .filter(::worthKeeping)
      .distinctBy { it.lowercase() }
      .take(MAX_FACTS)
  }

  /**
   * Whether a candidate is a fact about the person rather than about the conversation.
   *
   * A rule rather than a request. The prompt asks for the same thing, and a 2B model asked
   * nicely still produces "the user asked about the weather" - so what the prompt requests,
   * this enforces.
   */
  fun worthKeeping(fact: String): Boolean {
    val trimmed = fact.trim()
    if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_LENGTH) return false
    // A question is something nobody answered, not something to remember.
    if (trimmed.endsWith("?")) return false
    if (trimmed.split(Regex("\\s+")).size < MIN_WORDS) return false

    val lower = trimmed.lowercase()
    // Matched as prefixes rather than anywhere in the sentence: "Otto asked me to call Ben
    // every Friday" is a durable commitment, while "The user asked about trains" is a minute
    // of somebody's afternoon. The difference is where the phrase sits.
    return ABOUT_THE_CONVERSATION.none { lower.startsWith(it) }
  }

  /** Openings that describe the conversation instead of the person. */
  private val ABOUT_THE_CONVERSATION =
    listOf(
      "the user asked",
      "the user wanted",
      "the user requested",
      "the user said hello",
      "the user greeted",
      "the user is asking",
      "the user discussed",
      "we discussed",
      "we talked",
      "the conversation",
      "this conversation",
      "lin ",
      "the assistant",
      "i helped",
      "i told",
      "i explained",
      "no lasting facts",
      "nothing lasting",
    )

  private const val MIN_LENGTH = 10
  private const val MAX_LENGTH = 200
  private const val MIN_WORDS = 3
}

/**
 * Whether a fact is already held, by its words.
 *
 * The digest used to check this by meaning, with the embedding model - and with no embedding
 * model downloaded it checked nothing and kept nothing, silently, every time. Words are a
 * coarser test than meaning, but "Looper's sister is Mia" kept twice is a small price next to
 * every fact from every conversation thrown away.
 */
fun alreadyHeld(fact: String, held: Collection<String>): Boolean {
  val wanted = flattened(fact)
  return wanted.isNotEmpty() && held.any { flattened(it) == wanted }
}

private fun flattened(text: String): String =
  text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/**
 * One line saying what the last digest did, for the screen that lists what Lin remembers.
 *
 * Every way the digest could end used to end the same way: nothing saved, nothing said. That is
 * how it stopped working without anybody being able to tell - so now each run leaves a line.
 */
fun digestReport(whenMillis: Long, kept: Int, found: Int, why: String = ""): String {
  val time = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(whenMillis))
  return when {
    why.isNotBlank() -> "$time - nothing kept: $why"
    found == 0 -> "$time - nothing lasting in that conversation"
    else -> "$time - kept $kept of $found"
  }
}
