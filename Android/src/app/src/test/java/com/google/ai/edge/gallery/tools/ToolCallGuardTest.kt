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

package com.google.ai.edge.gallery.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The circuit breaker on a model that will not take no for an answer.
 *
 * The tool loop runs inside the engine, so there is no turn counter to cap: a tool that fails
 * structurally fails the same way every time, and a small model handed an error reliably tries
 * again. From outside that is an assistant that stops responding and a phone that gets warm.
 *
 * Both directions matter and the wrong one is easy to write. Too eager and a model working
 * honestly through three different pages gets cut off mid-task, which looks exactly like the app
 * refusing to do its job. Too lax and the loop is never broken at all.
 */
class ToolCallGuardTest {

  private var clock = 1_000_000L
  private fun guard(repeats: Int = 2, window: Long = 120_000L) =
    ToolCallGuard(repeatsAllowed = repeats, windowMillis = window, now = { clock })

  @Test
  fun letsTheFirstTriesThrough() {
    val g = guard()
    assertNull(g.loopBreaker("webSearch", "cats"))
    g.recordFailure("webSearch", "cats", "instance down")
    assertNull("one failure is not a loop", g.loopBreaker("webSearch", "cats"))
  }

  @Test
  fun breaksTheLoopOnTheThirdIdenticalCall() {
    val g = guard()
    g.recordFailure("webSearch", "cats", "instance down")
    g.recordFailure("webSearch", "cats", "instance down")
    val broken = g.loopBreaker("webSearch", "cats")
    assertNotNull("two failures is a loop", broken)
    assertEquals(true, broken!!["stop"])
    assertTrue("must carry why, or the model has nothing to tell the user",
      broken["result"].toString().contains("instance down"))
  }

  /**
   * Deliberately not a failure. A failure is what the model has already been handed twice and
   * worked around twice; a third tells it to try again, which is the loop.
   */
  @Test
  fun theLoopBreakerReadsAsAnAnswerNotAnError() {
    val g = guard()
    repeat(2) { g.recordFailure("openWebPage", "http://x", "timed out") }
    assertEquals("succeeded", g.loopBreaker("openWebPage", "http://x")!!["status"])
  }

  /** A model reading three different pages is working, not looping. */
  @Test
  fun differentArgumentsAreNotARepeat() {
    val g = guard()
    g.recordFailure("openWebPage", "http://a", "404")
    g.recordFailure("openWebPage", "http://b", "404")
    assertNull(g.loopBreaker("openWebPage", "http://c"))
    assertNull(g.loopBreaker("openWebPage", "http://a"))
  }

  @Test
  fun differentToolsAreNotARepeat() {
    val g = guard()
    g.recordFailure("webSearch", "cats", "down")
    g.recordFailure("showImageFromWeb", "cats", "down")
    assertNull(g.loopBreaker("webSearch", "cats"))
  }

  /** Asking the same thing again later in a conversation is a fresh request. */
  @Test
  fun forgetsOnceTheWindowHasPassed() {
    val g = guard()
    g.recordFailure("webSearch", "cats", "down")
    g.recordFailure("webSearch", "cats", "down")
    assertNotNull(g.loopBreaker("webSearch", "cats"))
    clock += 120_001L
    assertNull("a repeat long after the fact is a new request", g.loopBreaker("webSearch", "cats"))
  }

  /** A call that works is evidence the model is not stuck, so nothing is counted against it. */
  @Test
  fun successIsNeverCountedTowardsALoop() {
    val g = guard()
    // Only failures are ever recorded, so a tool called ten times successfully never trips it.
    assertNull(g.loopBreaker("webSearch", "cats"))
  }

  @Test
  fun aNewConversationStartsClean() {
    val g = guard()
    repeat(2) { g.recordFailure("webSearch", "cats", "down") }
    assertNotNull(g.loopBreaker("webSearch", "cats"))
    g.reset()
    assertNull(g.loopBreaker("webSearch", "cats"))
  }

  /** The bookkeeping must not grow without limit in a long conversation. */
  @Test
  fun doesNotGrowForEver() {
    val g = guard()
    for (i in 1..500) {
      g.recordFailure("openWebPage", "http://host$i", "404")
      clock += 1_000
    }
    // Still answers correctly for something recent, which is what the map is for.
    g.recordFailure("openWebPage", "http://recent", "404")
    g.recordFailure("openWebPage", "http://recent", "404")
    assertNotNull(g.loopBreaker("openWebPage", "http://recent"))
  }
}
