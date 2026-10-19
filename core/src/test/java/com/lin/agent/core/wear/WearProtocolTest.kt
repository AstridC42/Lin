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

package com.lin.agent.core.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WearProtocolTest {

  @Test
  fun `a question survives the trip from watch to phone`() {
    val q = WearProtocol.Question("q1", "What's the weather?")
    assertEquals(q, WearProtocol.questionFrom(WearProtocol.question(q)))
  }

  @Test
  fun `an empty or broken question is refused rather than answered`() {
    assertNull(WearProtocol.questionFrom(WearProtocol.question(WearProtocol.Question("q", "   "))))
    assertNull(WearProtocol.questionFrom("not json".toByteArray()))
  }

  @Test
  fun `a very long question is cut to what a watch could have sent`() {
    val long = "a".repeat(5_000)
    assertEquals(WearProtocol.LONGEST_QUESTION, WearProtocol.questionFrom(WearProtocol.question(WearProtocol.Question("q", long)))!!.text.length)
  }

  @Test
  fun `an answer and whether it worked survive the trip back`() {
    val a = WearProtocol.Answer("q1", "Sunny, 24 degrees.", ok = true)
    assertEquals(a, WearProtocol.answerFrom(WearProtocol.answer(a)))
    val failed = WearProtocol.answerFrom(WearProtocol.answer(WearProtocol.Answer("q2", "No model.", ok = false)))!!
    assertFalse(failed.ok)
  }
}
