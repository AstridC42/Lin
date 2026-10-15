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
 * Whether a model has stopped getting anywhere and is going round.
 *
 * Written from a phone. Asked "play something" with a `play_music` tool whose `what` was
 * required, Gemma 4 12B could not satisfy the tool and could not decide to stop, and wrote
 * the same four-line paragraph of reasoning over and over - "The user wants to Play
 * something. I should check what is currently playing..." - for as long as anybody watched.
 * Nothing in the loop was counting, so there was nothing to stop it: a server generates
 * until it hits its own limit, and `num_predict` is unset by default because overriding a
 * server's configuration is not this app's business.
 *
 * So the text is watched as it arrives. A run that repeats a long stretch of itself three
 * times is not making progress, and the only thing waiting for it is a person holding a
 * phone that is getting warm.
 *
 * Three sizes, largest first, because the repeating unit is whatever the model got stuck on:
 * a whole paragraph of reasoning, a sentence, or a few words.
 *
 * The repeats must be **back to back, at the end of what has arrived so far**, and that
 * precision is the difference between a useful guard and an infuriating one. Merely counting
 * how often a stretch appears flags ordinary writing: a numbered list whose items share a
 * stem, a table, a set of paragraphs built from a template. None of those is a model that
 * has stopped getting anywhere - they have different content between the repeats. A rut
 * does not: it emits the same block, then the same block again, with nothing in between,
 * and goes on doing it. Found by a test that caught the looser version flagging prose.
 */
fun looksLikeARut(text: String): Boolean {
  if (text.length < SHORTEST) return false
  val tail = text.takeLast(WINDOW)
  if (tail.length < PROBE * TIMES) return false

  // The length of the repeating block is found from the text rather than guessed at. Taking
  // the last few dozen characters and asking where they last appeared before now gives the
  // distance the model has travelled since writing them - which, if it is going round, is
  // exactly the size of the circle. Guessing fixed block sizes instead cannot work: the
  // paragraph a model repeats is whatever length it happens to be, and a probe that does not
  // land on its boundary never matches.
  val probe = tail.takeLast(PROBE)
  if (probe.isBlank()) return false
  val before = tail.lastIndexOf(probe, tail.length - PROBE - 1)
  if (before < 0) return false

  var period = tail.length - PROBE - before
  // A string that repeats every four characters also repeats every eight, and every twelve.
  // Scaling a short period up to the floor rather than rejecting it is what lets "no. no.
  // no." for four hundred characters be recognised - which it should be - while still
  // comparing a block big enough for the comparison to mean something.
  if (period < SHORTEST_BLOCK) period *= (SHORTEST_BLOCK + period - 1) / period
  val span = period * TIMES
  if (tail.length < span) return false

  // Back to back, at the end of what has arrived. See the note above about lists and tables.
  val chunk = tail.takeLast(span)
  return chunk == chunk.take(period).repeat(TIMES)
}

/**
 * What the user is shown if it happens twice.
 *
 * Phrased as something they can act on rather than as a fault report: a model that cannot
 * get anywhere with a request is usually one that was asked something it has no way to
 * narrow down, and saying it plainly is more use than "generation error".
 */
const val STUCK: String =
  "That went round in circles rather than getting to an answer. Try asking more specifically."

/**
 * What the model is told after being cut off, so the second attempt differs from the first.
 *
 * Specific about what happened and what to do instead, because a model that has just spent a
 * thousand tokens deliberating is one that needs permission to stop deliberating. It also
 * says not to mention the note: the user asked for music, not for an account of the
 * assistant's inner life.
 */
const val RUNAWAY_NUDGE: String =
  "System note, not from the user: you began repeating yourself and were stopped. Do not " +
    "think any further. Answer the user's last message now, in one or two sentences, using " +
    "a tool only if you are certain which one and what to pass it. If the request was too " +
    "vague to act on, ask them one short question instead. Do not mention this note."

/** Below this nothing is long enough to have repeated meaningfully. */
private const val SHORTEST = 400

/**
 * How much of the end is used to find where the model has been before.
 *
 * Long enough to be distinctive - a few dozen characters of prose repeat by chance almost
 * never - and short enough to sit inside any block worth calling a repetition.
 */
private const val PROBE = 48

/**
 * The smallest block that counts as going round.
 *
 * Low, because a model stuck emitting "no. no. no." for four hundred characters is every bit
 * as stuck as one repeating a paragraph, and the first version of this - which required a
 * block of two dozen characters - let that through. What keeps short repeats from being
 * flagged unfairly is [SHORTEST]: nobody reaches four hundred characters of a four-character
 * phrase by accident.
 */
private const val SHORTEST_BLOCK = 8

/** How much of the end to look at. Long enough for three paragraphs of deliberation. */
private const val WINDOW = 6_000

/** Twice could be a refrain or a quotation. Three times is a model that is not moving. */
private const val TIMES = 3

