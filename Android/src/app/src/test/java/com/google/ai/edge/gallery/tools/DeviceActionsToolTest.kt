/*
 * Copyright 2026 The Star Storyteller Authors
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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionsToolTest {

  @Test
  fun looksLikePhoneNumber_acceptsTheFormsPeopleActuallyType() {
    for (number in
      listOf(
        "+441632960961",
        "+1 (555) 010-4567",
        "0207 946 0958",
        "555-0100",
        "(555) 010-4567",
      )) {
      assertTrue(number, DeviceActionsTool.looksLikePhoneNumber(number))
    }
  }

  @Test
  fun looksLikePhoneNumber_rejectsNamesSoTheyGoThroughContactLookup() {
    for (name in listOf("Sam", "mum", "Dr. Chen", "Sam Smith", "", "  ")) {
      assertFalse(name, DeviceActionsTool.looksLikePhoneNumber(name))
    }
  }

  @Test
  fun looksLikePhoneNumber_rejectsSomethingTooShortToBeANumber() {
    assertFalse(DeviceActionsTool.looksLikePhoneNumber("911"))
    assertFalse(DeviceActionsTool.looksLikePhoneNumber("1"))
  }

  /**
   * Whether a number the model typed out is the one it was shown.
   *
   * The reported failure: asked to call somebody, the assistant looked them up, wrote the digits
   * out again in the wrong order, and dialled a stranger. Nothing downstream can catch that - the
   * number is well formed and the dialer has no opinion about whose it is - so this check is the
   * only thing between a transposed digit and a call to whoever owns it.
   *
   * It also has to stay quiet about numbers that merely differ, because every false positive
   * turns a call the user asked for into a question.
   */
  @Test
  fun garbledCopy_catchesTheSameDigitsInADifferentOrder() {
    assertTrue(DeviceActionsTool.looksLikeAGarbledCopy("0521234567", "0521234576"))
    assertTrue(DeviceActionsTool.looksLikeAGarbledCopy("0521243567", "0521234567"))
    // Fully shuffled, which is what "in a random order" describes.
    assertTrue(DeviceActionsTool.looksLikeAGarbledCopy("7654321250", "0521234567"))
  }

  @Test
  fun garbledCopy_catchesOneWrongMissingOrExtraDigit() {
    assertTrue(DeviceActionsTool.looksLikeAGarbledCopy("0521234568", "0521234567"))
    assertTrue(DeviceActionsTool.looksLikeAGarbledCopy("052123456", "0521234567"))
    assertTrue(DeviceActionsTool.looksLikeAGarbledCopy("05212345677", "0521234567"))
  }

  /** The number copied correctly is the common case and must go straight through. */
  @Test
  fun garbledCopy_saysNothingAboutAnExactCopy() {
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("0521234567", "0521234567"))
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("052-123-4567", "052 123 4567"))
  }

  /** A saved international number dictated nationally is the same number, not a slip. */
  @Test
  fun garbledCopy_matchesAcrossACountryCode() {
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("0521234567", "+972521234567"))
  }

  /** Two genuinely different numbers must still be dialable. */
  @Test
  fun garbledCopy_leavesADifferentNumberAlone() {
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("0509876543", "0521234567"))
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("0521234500", "0521234567"))
  }

  /** Too short to tell a transposition from a different number, so it does not try. */
  @Test
  fun garbledCopy_doesNotJudgeShortNumbers() {
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("911", "119"))
    assertFalse(DeviceActionsTool.looksLikeAGarbledCopy("1234", "4321"))
  }
}
