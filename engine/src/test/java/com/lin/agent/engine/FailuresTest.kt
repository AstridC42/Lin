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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling the one failure the model can fix from every failure it cannot.
 *
 * The strings here are copied from a phone, not invented: getting this wrong in the generous
 * direction would retry an out-of-memory error, and in the strict direction would throw away
 * a turn that a second attempt would have answered.
 */
class FailuresTest {

  @Test
  fun `the parser's own words`() {
    assertTrue(
      isMalformedToolCall(
        message = "The model tried to use a tool and wrote the request wrongly, so nothing ran.",
        detail =
          "Status Code: 3. Message: Failed to parse tool calls from code block: " +
            "call:find_photo{describe:<|\"|>a computer case with a glass panel<|\"|>, " +
            "with:<|\"|>,look:true}",
      )
    )
  }

  @Test
  fun `the other wording the engine uses`() {
    assertTrue(isMalformedToolCall("", "error: Failed to parse FC tool calls: FallThrough(...)"))
  }

  @Test
  fun `the readable half on its own`() {
    // Which half carries the evidence depends on where it failed, so either is enough.
    assertTrue(
      isMalformedToolCall("The model tried to use a tool and wrote the request wrongly.")
    )
  }

  @Test
  fun `a failure the model cannot fix is not retried`() {
    // Retrying any of these burns a second slow turn to fail the same way.
    assertFalse(isMalformedToolCall("This model is too large for this phone's free memory."))
    assertFalse(
      isMalformedToolCall(
        "That was too long for what is left of the model's memory on this conversation.",
        "Prefill input length exceeds available state entries (remaining capacity: 2906)",
      )
    )
    assertFalse(isMalformedToolCall("The model is still loading."))
    assertFalse(isMalformedToolCall(""))
  }
}
