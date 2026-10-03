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

package com.google.ai.edge.gallery.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failing turn says to the person holding the phone.
 *
 * Both strings below are real, taken from screenshots of the app in use. Neither tells anybody
 * what to do, and the second is forty lines of parser internals in the middle of a conversation.
 * The detail is still worth keeping - on a sideloaded build with no computer attached it is the
 * only account of what happened - so this adds a sentence rather than replacing anything.
 */
class EngineErrorsTest {

  private val tooLong =
    "Status Code: 3. Message: Input token ids are too long. Exceeding the maximum number of " +
      "tokens allowed: 4230 >= 4000"

  private val badToolCall =
    "Status Code: 3. Message: Failed to parse tool calls from response: " +
      "<|tool_call>call:web_search{query:doctor who lego}<tool_call|> with error: Failed to " +
      "parse FC tool calls: FallThrough(ParseCancelledError(NoAltError(NoViableAltError"

  @Test
  fun explainsRunningOutOfContext() {
    val shown = EngineErrors.explain(tooLong)
    assertTrue("must say what to do", shown.contains("Start a new chat"))
    assertTrue("must keep the detail", shown.contains("4230 >= 4000"))
  }

  @Test
  fun explainsAMalformedToolCall() {
    val shown = EngineErrors.explain(badToolCall)
    assertTrue("must say nothing ran", shown.contains("nothing ran"))
    assertTrue("must suggest something", shown.contains("Asking again"))
    assertTrue("must keep the detail", shown.contains("NoViableAltError"))
  }

  @Test
  fun explainsAModelThatIsNotLoaded() {
    assertTrue(EngineErrors.explain("LlmModelInstance is not initialized.").contains("not loaded"))
  }

  /** The explanation goes first, because that is the part worth reading. */
  @Test
  fun putsTheExplanationBeforeTheDetail() {
    val shown = EngineErrors.explain(tooLong)
    assertTrue(shown.indexOf("Start a new chat") < shown.indexOf("4230"))
  }

  /** Anything unrecognised must pass through untouched rather than being dressed up. */
  @Test
  fun leavesSomethingItDoesNotRecogniseAlone() {
    val odd = "Status Code: 7. Message: something nobody has seen before"
    assertEquals(odd, EngineErrors.explain(odd))
    assertEquals("", EngineErrors.explain(""))
  }

  /** The engine's casing is not something to depend on. */
  @Test
  fun doesNotDependOnCasing() {
    assertTrue(EngineErrors.explain("FAILED TO PARSE TOOL CALLS from x").contains("nothing ran"))
  }
}
