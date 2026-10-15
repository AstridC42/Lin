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
 * How a server should generate, as the user set it.
 *
 * Every field is nullable and null means **do not send it**, which is the whole shape of this
 * type. A server owner has configured their model; a default invented here and sent on every
 * request would quietly override that configuration with a number nobody chose. So Lin sends
 * only what the person actually set.
 *
 * It belongs to a server rather than to a turn, which is why [ServerBackend] takes one in its
 * constructor beside the model name instead of it riding on [TurnRequest]. The phone's own
 * model is not covered: it runs under LiteRT-LM with its own knobs, and pretending one set of
 * sliders drives both would be a lie on the screen.
 */
data class Sampling(
  val temperature: Double? = null,
  val topP: Double? = null,
  /** Ollama only. OpenAI has no such field and a faithful server rejects the request. */
  val topK: Int? = null,
  /** `num_ctx`. Also what Lin trims the conversation to, when it is set. */
  val contextTokens: Int? = null,
  val maxOutputTokens: Int? = null,
) {

  /**
   * The same settings with anything out of range brought back into it.
   *
   * Applied where the value is read rather than where it is typed. A settings screen can
   * validate, but the value also arrives from a restored backup written by another version,
   * and a temperature of 40 reaching a server is a request that fails rather than a reply
   * that is merely odd.
   */
  fun sane(): Sampling =
    Sampling(
      temperature = temperature?.coerceIn(0.0, 2.0),
      topP = topP?.coerceIn(0.0, 1.0),
      topK = topK?.coerceIn(1, 1_000),
      contextTokens = contextTokens?.coerceIn(256, 1_000_000),
      maxOutputTokens = maxOutputTokens?.coerceIn(1, 1_000_000),
    )

  /** Whether anything at all was set, for a screen that says "the server decides". */
  fun isEmpty(): Boolean =
    temperature == null &&
      topP == null &&
      topK == null &&
      contextTokens == null &&
      maxOutputTokens == null
}

/**
 * Greedy: one token, always the likeliest.
 *
 * The default for every request this app makes, and not an arbitrary one. Sampling at
 * temperature while a model is emitting an exact tool-call grammar is how a tool call comes
 * out malformed, which cost the app this one replaces several days of looking in the wrong
 * place. The user may now raise it - they asked to - and the settings screen says what it
 * costs.
 */
val GREEDY = Sampling(temperature = 0.0, topK = 1)

/**
 * What to actually send, given what the turn needs and what the user chose.
 *
 * The one rule worth stating: **a reply that must satisfy a schema is always generated
 * greedily, whatever the sliders say.** That is not Lin overriding the user for its own
 * convenience - a schema-constrained decode sampled at temperature produces output that fails
 * the schema, so the setting could only make that request worse, and the person asking for a
 * creative conversation is not asking for creative JSON.
 *
 * Everywhere else the user's values win, field by field, with the turn's own defaults filling
 * in whatever they left alone.
 */
fun samplingFor(chosen: Sampling, request: TurnRequest, structured: Boolean): Sampling {
  if (structured) return GREEDY
  val wanted = chosen.sane()
  return Sampling(
    temperature = wanted.temperature ?: request.temperature,
    topP = wanted.topP,
    topK = wanted.topK ?: request.topK,
    contextTokens = wanted.contextTokens,
    maxOutputTokens = wanted.maxOutputTokens,
  )
}

/**
 * Ollama's `options` block.
 *
 * Only the fields that were set. An option sent as null, or as a number nobody chose, is an
 * option overriding whatever the server's owner configured for that model.
 */
internal fun ollamaOptions(sampling: Sampling): org.json.JSONObject =
  org.json.JSONObject().apply {
    sampling.temperature?.let { put("temperature", it) }
    sampling.topP?.let { put("top_p", it) }
    sampling.topK?.let { put("top_k", it) }
    sampling.contextTokens?.let { put("num_ctx", it) }
    sampling.maxOutputTokens?.let { put("num_predict", it) }
  }

/**
 * The OpenAI dialect's sampling fields, added to [body].
 *
 * `top_k` is deliberately absent. It is not an OpenAI field: vLLM accepts it, llama-server
 * accepts it, and a faithful implementation rejects the whole request over an unknown
 * property - which would turn a slider nobody needed into a server that stopped answering.
 * The settings screen says which servers it applies to rather than this silently dropping it.
 */
internal fun openAiSampling(body: org.json.JSONObject, sampling: Sampling) {
  sampling.temperature?.let { body.put("temperature", it) }
  sampling.topP?.let { body.put("top_p", it) }
  sampling.maxOutputTokens?.let { body.put("max_tokens", it) }
}

/**
 * Settings as they are stored - strings - turned into values.
 *
 * Strings because the distinction that matters is "set" against "not set", and every numeric
 * type has a value that would have to stand in for absent. Zero is a real temperature and
 * means greedy; it must not also mean "the user did not say".
 *
 * Anything unparseable reads as not set rather than throwing. These are read on the way to
 * answering a question, and a settings value mangled by a restored backup must not be able
 * to stop Lin replying.
 */
fun samplingFrom(
  temperature: String? = null,
  topP: String? = null,
  topK: String? = null,
  contextTokens: String? = null,
  maxOutputTokens: String? = null,
): Sampling =
  Sampling(
      temperature = temperature?.trim()?.toDoubleOrNull(),
      topP = topP?.trim()?.toDoubleOrNull(),
      topK = topK?.trim()?.toIntOrNull(),
      contextTokens = contextTokens?.trim()?.toIntOrNull(),
      maxOutputTokens = maxOutputTokens?.trim()?.toIntOrNull(),
    )
    .sane()
