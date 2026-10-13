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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what somebody typed into an address, per kind of server.
 *
 * Every mistake here looks exactly like the server being down, which is the worst possible
 * failure mode for the one field that has to be right before anything works at all.
 */
class ServerKindTest {

  private fun chat(typed: String, kind: ServerKind) = serverUrl(typed, kind, Endpoint.CHAT)

  private fun models(typed: String, kind: ServerKind) = serverUrl(typed, kind, Endpoint.MODELS)

  // ---- the paths each dialect uses ----

  @Test
  fun ollamaKeepsItsOwnApi() {
    assertEquals("http://box:11434/api/chat", chat("box", ServerKind.OLLAMA))
    assertEquals("http://box:11434/api/tags", models("box", ServerKind.OLLAMA))
  }

  @Test
  fun vllmAndUnslothUseTheOpenAiRoutes() {
    assertEquals("http://box:8000/v1/chat/completions", chat("box", ServerKind.VLLM))
    assertEquals("http://box:8000/v1/models", models("box", ServerKind.VLLM))
    assertEquals("http://box:8080/v1/chat/completions", chat("box", ServerKind.UNSLOTH))
  }

  @Test
  fun onlyOllamaHasItsOwnBackend() {
    assertFalse(ServerKind.OLLAMA.speaksOpenAi)
    assertTrue(ServerKind.VLLM.speaksOpenAi)
    assertTrue(ServerKind.UNSLOTH.speaksOpenAi)
  }

  // ---- what people actually paste ----

  /**
   * `http://localhost:8000/v1` is what vLLM's own documentation shows.
   *
   * Taken literally it produces `/v1/v1/chat/completions`, which 404s - and a 404 from the one
   * address people copy from the README is the single most likely way for this to be unusable.
   */
  @Test
  fun anAddressEndingInV1IsNotDoubled() {
    assertEquals(
      "http://localhost:8000/v1/chat/completions",
      chat("http://localhost:8000/v1", ServerKind.VLLM),
    )
    assertEquals("http://localhost:8000/v1/models", models("http://localhost:8000/v1/", ServerKind.VLLM))
  }

  @Test
  fun aPastedEndpointIsStrippedWhicheverOneItWas() {
    // The address field is shared, so a pasted chat URL must still give a working model list.
    assertEquals(
      "http://box:8000/v1/models",
      models("http://box:8000/v1/chat/completions", ServerKind.VLLM),
    )
    assertEquals("http://box:11434/api/chat", chat("http://box:11434/api/tags", ServerKind.OLLAMA))
    assertEquals("http://box:11434/api/chat", chat("http://box:11434/api", ServerKind.OLLAMA))
  }

  /** `/v1/chat/completions` has to be stripped before `/v1`, or `/chat/completions` is left. */
  @Test
  fun theLongestEndpointIsStrippedFirst() {
    assertEquals(
      "http://box:8000/v1/chat/completions",
      chat("http://box:8000/v1/chat/completions", ServerKind.VLLM),
    )
  }

  @Test
  fun aMissingSchemeBecomesHttp() {
    assertEquals("http://192.168.1.9:8000/v1/models", models("192.168.1.9", ServerKind.VLLM))
  }

  @Test
  fun anExplicitPortIsLeftAlone() {
    assertEquals("http://box:9999/v1/models", models("box:9999", ServerKind.VLLM))
    assertEquals("http://box:9999/api/tags", models("box:9999", ServerKind.OLLAMA))
  }

  /** Under https something is terminating TLS, and that listens on 443, not on vLLM's port. */
  @Test
  fun httpsIsNotGivenADefaultPort() {
    assertEquals("https://models.example.com/v1/models", models("https://models.example.com", ServerKind.VLLM))
  }

  @Test
  fun aPathInFrontOfTheApiSurvives() {
    // A reverse proxy mounting the server under a prefix.
    assertEquals(
      "https://example.com/llm/v1/chat/completions",
      chat("https://example.com/llm", ServerKind.VLLM),
    )
  }

  @Test
  fun nothingTypedIsNoAddress() {
    assertNull(chat("", ServerKind.VLLM))
    assertNull(chat("   ", ServerKind.OLLAMA))
  }

  // ---- reading the stored setting ----

  /** Somebody upgrading has a working Ollama server and no stored kind. */
  @Test
  fun anUnsetKindIsOllamaSoAnUpgradeKeepsWorking() {
    assertEquals(ServerKind.OLLAMA, serverKindNamed(""))
    assertEquals(ServerKind.OLLAMA, serverKindNamed("something else"))
  }

  @Test
  fun aStoredKindRoundTripsLeniently() {
    for (kind in ServerKind.entries) {
      assertEquals(kind, serverKindNamed(kind.name))
      assertEquals(kind, serverKindNamed(" ${kind.name.lowercase()} "))
    }
  }
}
