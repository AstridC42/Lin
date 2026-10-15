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
 * How much the model may think before it answers.
 *
 * Gemma 4 can reason before replying, and on a phone that is a trade rather than an improvement:
 * thinking tokens are generated at the same speed as answer tokens, so a budget of four thousand
 * is four thousand tokens of waiting before the first word appears. Worth it for a question with
 * steps in it, wasted on "what time is it".
 *
 * So it is the user's choice, with a default of off, and the levels are described by what they
 * cost rather than by how clever they sound.
 */
enum class Reasoning {
  /** No thinking. The first token of the answer is the first token generated. */
  OFF,

  /** A little room to work, for a question with one step in it. */
  BRIEF,

  /** Enough for a real chain, and noticeably slower to first word. */
  THOROUGH,
}

/**
 * The level a stored setting names, defaulting to [Reasoning.OFF].
 *
 * Tolerant on purpose. The setting is persisted as a name so that the settings module need not
 * know about this one, which means a name written by a newer build can be read back by an older
 * one. Falling back beats throwing on a screen somebody is only trying to open - and falling
 * back to off rather than to a guess, because the unknown level might be a more expensive one
 * than they would have chosen.
 */
fun reasoningNamed(stored: String): Reasoning =
  Reasoning.entries.firstOrNull { it.name.equals(stored.trim(), ignoreCase = true) }
    ?: Reasoning.OFF

/** A thinking allowance, in tokens. */
data class ThinkingBudget(val tokens: Int)

/**
 * The budget for a turn, or null to let the model answer directly.
 *
 * Two rules, and the second is the one that matters.
 *
 * A turn with a response schema never thinks. The decoder is constrained to the schema for those
 * turns, and thinking is text that is not in it - so the model is asked to emit a chain of
 * thought through a grammar with no room for one. Whether the library drops the thinking, the
 * schema or the turn is not something that can be established off a device, and none of the
 * three is a good outcome. The one structured caller today is the digest, which needs no chain:
 * it is reading a transcript for facts, not solving anything.
 *
 * And OFF means off. A model asked to think with a zero budget is a worse-defined state than one
 * not asked at all - see the temperature in [OnDeviceBackend] for how that goes.
 */
internal fun thinkingFor(reasoning: Reasoning, structured: Boolean): ThinkingBudget? {
  if (structured) return null
  return when (reasoning) {
    Reasoning.OFF -> null
    Reasoning.BRIEF -> ThinkingBudget(BRIEF_TOKENS)
    Reasoning.THOROUGH -> ThinkingBudget(THOROUGH_TOKENS)
  }
}

/**
 * Deliberately modest numbers.
 *
 * These are budgets for a 2B model on a phone, not for a datacentre. At the ten tokens a second
 * a phone manages on CPU, [THOROUGH_TOKENS] is already most of a minute of silence before the
 * answer starts - which is why there is no level above it and why the default is off.
 */
private const val BRIEF_TOKENS = 256

private const val THOROUGH_TOKENS = 1024

/**
 * What Lin puts in Ollama's `think` field.
 *
 * Ollama takes a level - `"low"`, `"medium"`, `"high"`, `"max"` - as well as a boolean, and
 * Lin was sending `true` and nothing else. Two things were wrong with that and both were
 * reported from a phone.
 *
 * **Brief and Thorough did the same thing.** Picking the cheaper level changed nothing on a
 * server, so somebody who asked for a little thinking got as much as the model felt like -
 * which, on one occasion, was the same paragraph forever.
 *
 * **Off did not turn thinking off.** The field was omitted entirely when no thinking was
 * wanted, on the reasoning that an older Ollama rejects a field it does not know. But a
 * model that reasons by default reasons when nothing is said, so omitting it meant "off"
 * only for models that were not going to think anyway. `false` is now sent explicitly, and
 * that is the deliberate trade: a server too old to know the field answers 400, which is
 * visible and diagnosable, where thinking that will not switch off is neither.
 */
enum class OllamaThink(val wire: Any) {
  OFF(false),
  LOW("low"),
  HIGH("high"),
}

/**
 * The level for a turn.
 *
 * `medium` and `max` are deliberately unused. Three choices on the screen map to three
 * values here, and inventing a fourth that nothing offers would only be a value nobody can
 * pick. A structured turn never thinks, for the reason given on [thinkingFor].
 */
fun ollamaThink(reasoning: Reasoning, structured: Boolean): OllamaThink =
  when {
    structured -> OllamaThink.OFF
    reasoning == Reasoning.OFF -> OllamaThink.OFF
    reasoning == Reasoning.BRIEF -> OllamaThink.LOW
    else -> OllamaThink.HIGH
  }
