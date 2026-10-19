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

package com.lin.agent.core.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the hinge, on the one device this app is actually used on. */
class PostureTest {

  @Test
  fun `a flat phone is a flat phone, whatever its hinge says`() {
    // A layout that split itself because a foldable exists would be wrong on every other
    // device, and wrong on this one most of the time.
    assertEquals(Posture.FLAT, postureOf(halfOpened = false, hingeIsHorizontal = true))
    assertEquals(Posture.FLAT, postureOf(halfOpened = false, hingeIsHorizontal = false))
  }

  @Test
  fun `half open with the fold across the middle is Flex mode`() {
    assertEquals(Posture.TABLETOP, postureOf(halfOpened = true, hingeIsHorizontal = true))
  }

  @Test
  fun `half open the other way is a book`() {
    assertEquals(Posture.BOOK, postureOf(halfOpened = true, hingeIsHorizontal = false))
  }

  @Test
  fun `only tabletop splits top from bottom`() {
    // In book posture the halves are side by side: splitting top from bottom would cut both
    // pages in half, which is worse than not splitting at all.
    assertTrue(splitAcrossTheCrease(Posture.TABLETOP))
    assertFalse(splitAcrossTheCrease(Posture.BOOK))
    assertFalse(splitAcrossTheCrease(Posture.FLAT))
  }

  @Test
  fun `a phone gets one pane, with the list in the drawer`() {
    assertEquals(null, listPaneWidth(411, Posture.FLAT))
    assertEquals(null, listPaneWidth(599, Posture.BOOK, hingeLeftDp = 300))
  }

  @Test
  fun `an unfolded screen keeps the list beside the conversation, about a third wide`() {
    // A Fold's inner screen, flat open: the hinge is ignored.
    assertEquals(299, listPaneWidth(882, Posture.FLAT, hingeLeftDp = 441))
    // Narrow two-pane windows keep the list readable; wide ones do not let it sprawl.
    assertEquals(260, listPaneWidth(600, Posture.FLAT))
    assertEquals(380, listPaneWidth(1280, Posture.FLAT))
  }

  @Test
  fun `half open like a book, the split sits on the hinge`() {
    assertEquals(441, listPaneWidth(882, Posture.BOOK, hingeLeftDp = 441))
    // A hinge that would leave either side unusably narrow is not followed.
    assertEquals(299, listPaneWidth(882, Posture.BOOK, hingeLeftDp = 100))
    assertEquals(299, listPaneWidth(882, Posture.BOOK, hingeLeftDp = null))
  }
}
