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

class DelegationTest {

  @Test
  fun `the phone answering with a server to hand up to is the case this exists for`() {
    assertTrue(
      delegationIsWorthIt(hasServerModel = true, wantsServerToAnswer = false, serverIsAnswering = false)
    )
  }

  @Test
  fun `the server answering must not delegate to itself`() {
    // The bug this file was written for. Lin would open a second connection to the same
    // server, send the same model a restatement of the question it is in the middle of, wait
    // for it, and repeat the answer - slower than not delegating, with a tool card on screen
    // saying a tool ran for nothing.
    assertFalse(
      delegationIsWorthIt(hasServerModel = true, wantsServerToAnswer = true, serverIsAnswering = true)
    )
  }

  @Test
  fun `a server that was meant to answer and did not is not worth delegating to`() {
    // answerOnServer is on but the phone is replying, which only happens when the server
    // could not be reached - so a delegation to it would fail too, after a timeout the
    // person waits through.
    assertFalse(
      delegationIsWorthIt(hasServerModel = true, wantsServerToAnswer = true, serverIsAnswering = false)
    )
  }

  @Test
  fun `nothing configured means nothing to hand to`() {
    assertFalse(
      delegationIsWorthIt(hasServerModel = false, wantsServerToAnswer = false, serverIsAnswering = false)
    )
    assertFalse(
      delegationIsWorthIt(hasServerModel = false, wantsServerToAnswer = true, serverIsAnswering = false)
    )
  }

  @Test
  fun `a server answering without being configured cannot happen and is refused anyway`() {
    // Defensive: the three flags come from different reads and a combination that should be
    // impossible must not be the one that turns the tool back on.
    assertFalse(
      delegationIsWorthIt(hasServerModel = false, wantsServerToAnswer = false, serverIsAnswering = true)
    )
  }
}
