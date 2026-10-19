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

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

class ToolCallStreamTest {

  @Test
  fun `a fingerprint names the call it was made from`() {
    val fingerprint = fingerprintOf(ToolInvocation("unread_messages", emptyMap()))
    // The assertion that catches the bug this file was written for. An escaped interpolation
    // produced the literal text "${it.name}(${it.arguments})" for every call, which is a
    // perfectly good-looking string that happens to be the same one every time.
    assertEquals("unread_messages({})", fingerprint)
  }

  @Test
  fun `two different calls have two different fingerprints`() {
    assertNotEquals(
      fingerprintOf(ToolInvocation("unread_messages", emptyMap())),
      fingerprintOf(ToolInvocation("calendar_events", mapOf("days" to 1))),
    )
  }

  @Test
  fun `the same tool with different arguments is two calls`() {
    assertNotEquals(
      fingerprintOf(ToolInvocation("phone_setting", mapOf("setting" to "wifi"))),
      fingerprintOf(ToolInvocation("phone_setting", mapOf("setting" to "bluetooth"))),
    )
  }

  @Test
  fun `two tools asked for in one message both come through`() {
    val stream = FreshToolCalls()
    val asked =
      listOf(
        ToolInvocation("unread_messages", emptyMap()),
        ToolInvocation("calendar_events", mapOf("days" to 1)),
      )

    // This is the whole bug: with one shared constant fingerprint the second call was filtered
    // out, the model was handed a result for one of the two tools it asked for, and it told the
    // user it could not read the result of a call that was never executed.
    assertEquals(asked, stream.fresh(asked))
  }

  @Test
  fun `a call repeated on a later delta is only run once`() {
    val stream = FreshToolCalls()
    val call = ToolInvocation("unread_messages", emptyMap())

    assertEquals(listOf(call), stream.fresh(listOf(call)))
    assertEquals(emptyList<ToolInvocation>(), stream.fresh(listOf(call)))
  }

  @Test
  fun `a repeat alongside something new keeps only the new one`() {
    val stream = FreshToolCalls()
    val first = ToolInvocation("unread_messages", emptyMap())
    val second = ToolInvocation("weather", mapOf("days_ahead" to 0))

    stream.fresh(listOf(first))
    assertEquals(listOf(second), stream.fresh(listOf(first, second)))
  }

  @Test
  fun `each exchange starts over`() {
    val call = ToolInvocation("unread_messages", emptyMap())
    // A new stream per exchange is the point: asking the same thing again in a later exchange
    // is the model following up, not a duplicate delta.
    assertEquals(listOf(call), FreshToolCalls().fresh(listOf(call)))
    assertEquals(listOf(call), FreshToolCalls().fresh(listOf(call)))
  }
}
