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

package com.lin.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking a summoned question once.
 *
 * The bug this exists for: "Hey Lin, what's the time", then unfolding the phone, and the
 * question going a second time because the screen was recreated and ran its start-up effect
 * again. The rule has to hold both ways round - a recreated screen must not re-ask, and
 * somebody repeating themselves must.
 */
class SummonTest {

  @Test
  fun `a recreated screen keeps the token it was already working on`() {
    // The recreation case. The intent is redelivered and would otherwise look like a new
    // summon, which is exactly how the same question got asked twice.
    assertEquals("already", Summon.tokenFor(saved = "already", fromIntent = "redelivered", fresh = "new"))
  }

  @Test
  fun `a summon that minted a token is that token`() {
    assertEquals("from-wake", Summon.tokenFor(saved = null, fromIntent = "from-wake", fresh = "new"))
  }

  @Test
  fun `a summon that brought nothing gets a fresh one`() {
    // The system's assistant intent carries nothing of ours, which is the ordinary case.
    assertEquals("new", Summon.tokenFor(saved = null, fromIntent = null, fresh = "new"))
  }

  @Test
  fun `blank is not a token`() {
    // An extra set to empty is absent, not a token. Reading it as one would make every launch
    // the same summon and the second question would never be asked.
    assertEquals("from-wake", Summon.tokenFor(saved = "", fromIntent = "from-wake", fresh = "new"))
    assertEquals("new", Summon.tokenFor(saved = "  ", fromIntent = "", fresh = "new"))
  }

  @Test
  fun `the first summon of a screen has never been handled`() {
    assertFalse(Summon.alreadyHandled(previous = null, token = "one"))
  }

  @Test
  fun `the same token twice is the same summon`() {
    assertTrue(Summon.alreadyHandled(previous = "one", token = "one"))
  }

  @Test
  fun `saying the same thing again is a new summon`() {
    // Two wakes, same words, different tokens - so the second question is asked. This is why
    // the words themselves cannot be what is remembered.
    assertFalse(Summon.alreadyHandled(previous = "wake-1", token = "wake-2"))
  }
}
