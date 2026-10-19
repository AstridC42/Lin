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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the engine's failures into something a person can act on.
 *
 * The engine writes for whoever is debugging the engine, and these arrive in the middle of
 * somebody's conversation. Two of them reached a user of the first version: a token-limit message
 * that read like a crash when it was a setting, and a parser error with an ANTLR stack in it that
 * told nobody anything.
 */
class EngineErrorsTest {

  @Test
  fun outOfMemoryPointsAtTheSmallerModel() {
    val message = explain(OutOfMemoryError())
    assertTrue(message, message.contains("too large"))
    assertTrue("should say what to do about it", message.contains("smaller"))
  }

  /** An OOM arriving as a message rather than as the Error type still has to be recognised. */
  @Test
  fun outOfMemoryIsRecognisedByWordingToo() {
    val message = explain(RuntimeException("Native allocation failed: out of memory"))
    assertTrue(message, message.contains("too large"))
  }

  @Test
  fun aFullContextSaysToStartANewConversation() {
    val raw = "Input token ids are too long. Exceeding the maximum number of tokens: 4230 >= 4000"
    val message = explain(RuntimeException(raw))
    assertTrue(message, message.contains("no longer fits"))
    assertTrue("should say what to do", message.contains("new one"))
  }

  @Test
  fun aMalformedToolCallSaysNothingRanAndToAskAgain() {
    val raw = "Failed to parse tool calls from response: <|tool_call>call:web_search{...}"
    val message = explain(RuntimeException(raw))
    assertTrue(message, message.contains("nothing ran"))
  }

  /**
   * Anything unrecognised comes through unchanged.
   *
   * Deliberate: when the engine rewords something, the match stops firing and the user sees the
   * original rather than a wrong explanation. On a phone with no computer attached that text is
   * the only account of the failure there is.
   */
  @Test
  fun anUnknownFailureKeepsItsOwnWords() {
    assertEquals("something quite new", explain(RuntimeException("something quite new")))
  }

  @Test
  fun aFailureWithNoMessageAtAllStillSaysSomething() {
    assertTrue(explain(RuntimeException()).isNotEmpty())
  }

  /**
   * Greedy decoding must not ask for a zero temperature.
   *
   * It reads like a harmless way to say "no randomness", and on the GPU it is not: the sampler's
   * annealing step rejects zero, and the turn comes back as "Status Code: 13 ... Task failed
   * with state: 7" - which names neither the sampler nor the temperature. The CPU path tolerated
   * it, so this stayed hidden until the GPU started working.
   *
   * Determinism comes from topK instead, which this also pins: one candidate is the argmax.
   */
  @Test
  fun greedyDecodingDoesNotUseAZeroTemperature() {
    assertEquals("one candidate is what makes it greedy", 1, OnDeviceBackend.GREEDY_TOP_K)
    assertNotEquals(
      "the GPU sampler rejects a zero annealing temperature",
      0.0,
      OnDeviceBackend.SAFE_TEMPERATURE,
      0.0,
    )
  }

  /** A failure whose detail repeats its message shows one line, not the same line twice. */
  @Test
  fun aFailureThatExplainsItselfIsNotPrintedTwice() {
    val raw = "Status Code: 13. Message: Task failed with state: 7"
    assertEquals(raw, TurnEvent.Failed(raw, raw).display)
    assertEquals(raw, TurnEvent.Failed(raw, " $raw ").display)
  }

  /** A detail that adds something is kept, underneath the sentence. */
  @Test
  fun aDetailThatAddsSomethingIsKept() {
    val failure = TurnEvent.Failed("The model is too large for this phone.", "OutOfMemoryError")
    assertEquals(
      "The model is too large for this phone.\n\nOutOfMemoryError",
      failure.display,
    )
  }

  @Test
  fun noDetailIsJustTheMessage() {
    assertEquals("Gone wrong.", TurnEvent.Failed("Gone wrong.").display)
  }
}
