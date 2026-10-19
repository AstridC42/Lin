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

package com.lin.agent.core.screen

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SendPolicyTest {

  @Test
  fun `nothing chosen means Lin does not press send`() {
    assertEquals(TapPolicy.NEVER, tapPolicyNamed(null))
    assertEquals(TapPolicy.NEVER, tapPolicyNamed(""))
    assertEquals(TapPolicy.NEVER, tapPolicyNamed("   "))
    // A value written by a newer build and read by an older one reads as the default rather
    // than throwing on a screen somebody is trying to open.
    assertEquals(TapPolicy.NEVER, tapPolicyNamed("WHENEVER_IT_FEELS_RIGHT"))
  }

  @Test
  fun `a stored choice is read back`() {
    assertEquals(TapPolicy.WHEN_ASKED, tapPolicyNamed("WHEN_ASKED"))
    assertEquals(TapPolicy.ALWAYS, tapPolicyNamed("always"))
    assertEquals(TapPolicy.NEVER, tapPolicyNamed(" never "))
  }

  @Test
  fun `never means never, whatever was said`() {
    assertFalse(maySendItself(TapPolicy.NEVER, askedToSend = true))
    assertFalse(maySendItself(TapPolicy.NEVER, askedToSend = false))
  }

  @Test
  fun `when asked follows the person's words`() {
    assertTrue(maySendItself(TapPolicy.WHEN_ASKED, askedToSend = true))
    assertFalse(maySendItself(TapPolicy.WHEN_ASKED, askedToSend = false))
  }

  @Test
  fun `always still needs a message to have been written`() {
    assertTrue(maySendItself(TapPolicy.ALWAYS, askedToSend = false))
  }

  @Test
  fun `an instruction to send reads as one`() {
    assertTrue(soundsLikeSendIt("send dad a message that I'm running late"))
    assertTrue(soundsLikeSendIt("text Ben that I'll be there at six"))
    assertTrue(soundsLikeSendIt("tell mum I landed"))
    assertTrue(soundsLikeSendIt("reply to Sarah that it's fine"))
  }

  @Test
  fun `asking for a draft does not`() {
    assertFalse(soundsLikeSendIt("draft something to dad"))
    assertFalse(soundsLikeSendIt("write a message to Ben about dinner"))
    assertFalse(soundsLikeSendIt("compose a reply to Sarah"))
  }

  @Test
  fun `a sentence that says both is read as a draft`() {
    // Somebody thinking aloud. The safe reading of a sentence containing both words is the one
    // that leaves the words on screen, because one of the two readings cannot be undone.
    assertFalse(soundsLikeSendIt("draft a message to dad and send it"))
    assertFalse(soundsLikeSendIt("write it out but don't send it yet"))
  }

  @Test
  fun `an explicit refusal to send is honoured`() {
    assertFalse(soundsLikeSendIt("message dad, don't send it yet"))
    assertFalse(soundsLikeSendIt("text Ben without sending"))
  }

  @Test
  fun `nothing said is not an instruction`() {
    assertFalse(soundsLikeSendIt(""))
    assertFalse(soundsLikeSendIt("   "))
    assertFalse(soundsLikeSendIt("what's the weather"))
  }
}
