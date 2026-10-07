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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test seam itself, tested.
 *
 * Small, but it earns its place: every later test of the agent loop runs against this, so if its
 * streaming contract is wrong they are all quietly wrong too. The contract is that tokens
 * concatenate to exactly what [TurnEvent.Done] reports.
 */
class FakeBackendTest {

  @Test
  fun tokensConcatenateToTheFinalText() = runTest {
    val backend = FakeBackend(reply = { "one two three" })
    val events = backend.converse(TurnRequest(messages = emptyList())).toList()

    val streamed = events.filterIsInstance<TurnEvent.Token>().joinToString("") { it.text }
    val done = events.filterIsInstance<TurnEvent.Done>().single()
    assertEquals("one two three", done.fullText)
    assertEquals(done.fullText, streamed)
  }

  @Test
  fun itEchoesTheLastUserMessage() = runTest {
    val backend = FakeBackend()
    val request =
      TurnRequest(
        messages =
          listOf(
            Message(Message.Role.USER, "first"),
            Message(Message.Role.ASSISTANT, "reply"),
            Message(Message.Role.USER, "second"),
          )
      )
    val done =
      backend.converse(request).toList().filterIsInstance<TurnEvent.Done>().single()
    assertTrue("should echo the latest user turn", done.fullText.contains("second"))
  }

  @Test
  fun aFailureIsReportedRatherThanThrown() = runTest {
    val backend = FakeBackend(failWith = "no model loaded")
    val events = backend.converse(TurnRequest(messages = emptyList())).toList()
    assertEquals("no model loaded", events.filterIsInstance<TurnEvent.Failed>().single().message)
    assertTrue(events.none { it is TurnEvent.Done })
  }

  /** Greedy by default, because sampling while emitting an exact grammar is how tool calls break. */
  @Test
  fun theDefaultRequestIsGreedy() {
    val request = TurnRequest(messages = emptyList())
    assertEquals(0.0, request.temperature, 0.0)
    assertEquals(1, request.topK)
  }
}
