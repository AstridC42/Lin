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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {

  private fun ask(schema: String? = null) =
    TurnRequest(messages = listOf(Message(Message.Role.USER, "hi")), responseSchema = schema)

  @Test
  fun `nothing set means nothing is sent`() {
    // The shape of the whole type. A server owner configured their model; a default invented
    // here and sent on every request would quietly override it with a number nobody chose.
    val options = ollamaOptions(Sampling())
    assertEquals(0, options.length())
    assertTrue(Sampling().isEmpty())
  }

  @Test
  fun `what the user set is what is sent`() {
    val options =
      ollamaOptions(
        Sampling(temperature = 0.8, topP = 0.95, topK = 40, contextTokens = 8192, maxOutputTokens = 512)
      )
    assertEquals(0.8, options.getDouble("temperature"), 0.0001)
    assertEquals(0.95, options.getDouble("top_p"), 0.0001)
    assertEquals(40, options.getInt("top_k"))
    assertEquals(8192, options.getInt("num_ctx"))
    assertEquals(512, options.getInt("num_predict"))
  }

  @Test
  fun `a field left alone is absent rather than null`() {
    val options = ollamaOptions(Sampling(temperature = 0.4))
    assertTrue(options.has("temperature"))
    assertFalse(options.has("top_p"))
    assertFalse(options.has("num_ctx"))
  }

  @Test
  fun `the openai dialect never sends top_k`() {
    // Not an OpenAI field. vLLM and llama-server accept it; a faithful implementation
    // rejects the whole request over an unknown property, which would turn a slider nobody
    // needed into a server that stopped answering.
    val body = JSONObject()
    openAiSampling(body, Sampling(temperature = 0.7, topK = 40, maxOutputTokens = 256))
    assertFalse(body.has("top_k"))
    assertEquals(0.7, body.getDouble("temperature"), 0.0001)
    assertEquals(256, body.getInt("max_tokens"))
  }

  @Test
  fun `the openai dialect does not invent a context size either`() {
    val body = JSONObject()
    openAiSampling(body, Sampling(contextTokens = 8192))
    assertEquals(0, body.length())
  }

  // ---- what is actually used ----

  @Test
  fun `a schema-constrained reply is always greedy`() {
    // Not Lin overriding the user for convenience: a schema-constrained decode sampled at
    // temperature produces output that fails the schema, so the setting could only make that
    // request worse. Nobody asking for a creative conversation is asking for creative JSON.
    val used = samplingFor(Sampling(temperature = 1.2, topK = 80), ask(schema = "{}"), structured = true)
    assertEquals(0.0, used.temperature!!, 0.0001)
    assertEquals(1, used.topK)
  }

  @Test
  fun `without a schema the user's values win`() {
    val used = samplingFor(Sampling(temperature = 0.8, topK = 40), ask(), structured = false)
    assertEquals(0.8, used.temperature!!, 0.0001)
    assertEquals(40, used.topK)
  }

  @Test
  fun `the turn's own defaults fill in whatever was left alone`() {
    // The request is greedy by default and that default has to survive a user who set only
    // top-p, or every conversation silently starts sampling.
    val used = samplingFor(Sampling(topP = 0.9), ask(), structured = false)
    assertEquals(0.0, used.temperature!!, 0.0001)
    assertEquals(1, used.topK)
    assertEquals(0.9, used.topP!!, 0.0001)
  }

  // ---- values from a backup written by another version ----

  @Test
  fun `a temperature out of range is brought back into it`() {
    assertEquals(2.0, Sampling(temperature = 40.0).sane().temperature!!, 0.0001)
    assertEquals(0.0, Sampling(temperature = -3.0).sane().temperature!!, 0.0001)
  }

  @Test
  fun `top-p stays a probability`() {
    assertEquals(1.0, Sampling(topP = 9.0).sane().topP!!, 0.0001)
    assertEquals(0.0, Sampling(topP = -1.0).sane().topP!!, 0.0001)
  }

  @Test
  fun `top-k and the sizes stay positive`() {
    assertEquals(1, Sampling(topK = 0).sane().topK)
    assertEquals(256, Sampling(contextTokens = 1).sane().contextTokens)
    assertEquals(1, Sampling(maxOutputTokens = 0).sane().maxOutputTokens)
  }

  @Test
  fun `sanity does not invent values for fields nobody set`() {
    val sane = Sampling().sane()
    assertNull(sane.temperature)
    assertNull(sane.topP)
    assertNull(sane.topK)
    assertNull(sane.contextTokens)
    assertNull(sane.maxOutputTokens)
  }

  @Test
  fun `a blank setting is not a setting`() {
    assertTrue(samplingFrom().isEmpty())
    assertTrue(samplingFrom(temperature = "", topP = "   ").isEmpty())
  }

  @Test
  fun `zero is a real temperature and not the absence of one`() {
    // Which is why these are stored as strings. Zero means greedy; it must not also mean
    // "the user did not say", or greedy could never be chosen deliberately.
    assertEquals(0.0, samplingFrom(temperature = "0").temperature!!, 0.0001)
  }

  @Test
  fun `a mangled value reads as unset rather than throwing`() {
    // Read on the way to answering a question. A settings value damaged by a restored backup
    // must not be able to stop Lin replying.
    assertNull(samplingFrom(temperature = "warm").temperature)
    assertNull(samplingFrom(topK = "40.5.1").topK)
  }

  @Test
  fun `stored values are brought into range on the way through`() {
    assertEquals(2.0, samplingFrom(temperature = "99").temperature!!, 0.0001)
  }

  @Test
  fun `a nonsense stored value cannot reach the server`() {
    // sane() is applied where the value is read, not where it is typed, because it also
    // arrives from a restored backup written by another version.
    val used = samplingFor(Sampling(temperature = 99.0), ask(), structured = false)
    assertEquals(2.0, used.temperature!!, 0.0001)
  }
}
