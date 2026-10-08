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

package com.lin.agent.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staged-action policy, which is the measurement this phase is judged on.
 *
 * A call is the one thing in this app that cannot be undone. Everything else - a wrong answer, a
 * failed download, a crash - costs time; a call placed to a stranger at two in the morning
 * because a 2B model transposed two digits costs something else. So the rule is tested from both
 * ends: that a call is staged by default, and that the one path which places it directly cannot
 * be reached by accident.
 */
class CallPolicyTest {

  private val sam = KnownNumber("Sam Smith", "07700 900123")
  private val book = listOf(sam, KnownNumber("Mum", "07700 900456"))

  // ---- the default ----

  /** The rule. Nothing about a model's confidence changes it. */
  @Test
  fun aCallIsStagedInTheDialerByDefault() {
    val decision =
      decideCall(
        requested = "Sam",
        matches = listOf(sam),
        optedInToDirectCalls = false,
        callPermissionGranted = false,
      )
    val staged = decision as CallDecision.OpenDialer
    assertEquals("07700 900123", staged.number)
    assertEquals("Sam Smith", staged.displayName)
  }

  /**
   * Both conditions are required, and neither implies the other.
   *
   * The permission can be granted by a dialog somebody barely read, and the opt-in can survive
   * on a phone where the permission was revoked in settings afterwards.
   */
  @Test
  fun neitherTheOptInNorThePermissionIsEnoughOnItsOwn() {
    assertTrue(
      decideCall("Sam", listOf(sam), optedInToDirectCalls = true, callPermissionGranted = false)
        is CallDecision.OpenDialer
    )
    assertTrue(
      decideCall("Sam", listOf(sam), optedInToDirectCalls = false, callPermissionGranted = true)
        is CallDecision.OpenDialer
    )
  }

  @Test
  fun bothTogetherPlaceTheCall() {
    val decision =
      decideCall("Sam", listOf(sam), optedInToDirectCalls = true, callPermissionGranted = true)
    assertEquals(CallDecision.PlaceCall("07700 900123", "Sam Smith"), decision)
  }

  /**
   * The two reasons for stopping look identical from the outside - a dialer with a number in it.
   * Somebody who asked for a call and got a dialer deserves to know which one it was.
   */
  @Test
  fun theReasonForStoppingSaysWhichOfTheTwoItWas() {
    val notOptedIn =
      decideCall("Sam", listOf(sam), optedInToDirectCalls = false, callPermissionGranted = true)
        as CallDecision.OpenDialer
    val noPermission =
      decideCall("Sam", listOf(sam), optedInToDirectCalls = true, callPermissionGranted = false)
        as CallDecision.OpenDialer

    assertTrue(notOptedIn.why, notOptedIn.why.contains("Direct calling is off"))
    assertTrue(noPermission.why, noPermission.why.contains("permission"))
  }

  // ---- the number the model made up ----

  /**
   * The failure that cannot be taken back.
   *
   * Two digits transposed produces a number that is entirely valid and belongs to a stranger.
   * Nothing about it looks wrong until somebody answers.
   */
  @Test
  fun aNumberThatIsNearlyAContactsIsQueriedRatherThanDialled() {
    // Sam's number with the last two digits swapped.
    val decision =
      decideCall(
        requested = "07700 900132",
        matches = emptyList(),
        addressBook = book,
        optedInToDirectCalls = true,
        callPermissionGranted = true,
      )
    val ask = decision as CallDecision.AskFirst
    assertTrue(ask.question, ask.question.contains("Sam Smith"))
    assertTrue("it must say nothing happened", ask.question.contains("Nothing was dialled"))
  }

  /** And the guard holds even with both authorisations given - it is not an opt-out. */
  @Test
  fun theGarbledGuardIsNotBypassedByOptingIn() {
    val decision =
      decideCall(
        "07700 900132",
        emptyList(),
        book,
        optedInToDirectCalls = true,
        callPermissionGranted = true,
      )
    assertTrue(decision is CallDecision.AskFirst)
  }

  /** A number correctly copied out of the contacts is not a near miss. */
  @Test
  fun aCorrectlyCopiedNumberIsDialledNormally() {
    val decision =
      decideCall(
        "07700900123",
        emptyList(),
        book,
        optedInToDirectCalls = false,
        callPermissionGranted = false,
      )
    assertTrue(decision is CallDecision.OpenDialer)
  }

  /** A number that resembles nobody is the user's own, and is staged like any other. */
  @Test
  fun anUnrelatedNumberIsStagedRatherThanQuestioned() {
    val decision =
      decideCall(
        "0208 555 0199",
        emptyList(),
        book,
        optedInToDirectCalls = false,
        callPermissionGranted = false,
      )
    assertTrue(decision is CallDecision.OpenDialer)
  }

  // ---- ambiguity and nonsense ----

  @Test
  fun twoPeopleMatchingMeansAskingRatherThanPicking() {
    val decision =
      decideCall(
        "Sam",
        listOf(sam, KnownNumber("Sam Jones", "07700 900789")),
        optedInToDirectCalls = true,
        callPermissionGranted = true,
      )
    val ask = decision as CallDecision.AskFirst
    assertTrue(ask.question, ask.question.contains("Sam Smith"))
    assertTrue(ask.question, ask.question.contains("Sam Jones"))
  }

  /** One person with a mobile and a work number is not ambiguous enough to interrupt over. */
  @Test
  fun onePersonWithTwoNumbersIsNotAmbiguous() {
    val decision =
      decideCall(
        "Sam",
        listOf(sam, KnownNumber("Sam Smith", "0208 555 0100", label = "work")),
        optedInToDirectCalls = false,
        callPermissionGranted = false,
      )
    assertTrue(decision is CallDecision.OpenDialer)
  }

  @Test
  fun aNameThatMatchedNothingIsRefusedRatherThanDialled() {
    val decision =
      decideCall(
        "whoever did the plumbing",
        emptyList(),
        book,
        optedInToDirectCalls = true,
        callPermissionGranted = true,
      )
    assertTrue(decision is CallDecision.Refuse)
  }

  @Test
  fun nothingAtAllIsRefused() {
    assertTrue(
      decideCall("   ", emptyList(), optedInToDirectCalls = true, callPermissionGranted = true)
        is CallDecision.Refuse
    )
  }

  // ---- the pieces underneath ----

  @Test
  fun whatCountsAsANumber() {
    assertTrue(looksLikeANumber("+44 7700 900123"))
    assertTrue(looksLikeANumber("(020) 7946-0958"))
    assertFalse("too short to be a phone number", looksLikeANumber("12345"))
    assertFalse(looksLikeANumber("Sam"))
    assertFalse("a letter anywhere means a name", looksLikeANumber("07700 900123 mobile"))
    assertFalse(looksLikeANumber(""))
  }

  /** An international and a local form of one number must not read as a near miss. */
  @Test
  fun theSameNumberWrittenTwoWaysIsNotAGarbledCopy() {
    assertFalse(looksLikeAGarbledCopy("+972 50 123 4567", "050 123 4567"))
    assertFalse(looksLikeAGarbledCopy("07700900123", "+44 7700 900123"))
  }

  @Test
  fun reorderedAndMistypedDigitsAreBothCaught() {
    assertTrue("transposed", looksLikeAGarbledCopy("07700 900132", "07700 900123"))
    assertTrue("one digit wrong", looksLikeAGarbledCopy("07700 900124", "07700 900123"))
  }

  @Test
  fun twoGenuinelyDifferentNumbersAreNotANearMiss() {
    assertFalse(looksLikeAGarbledCopy("07700 900999", "07700 900123"))
  }

  /** Too few digits to compare is not evidence of anything, so it must not be a near miss. */
  @Test
  fun shortStringsAreNeverComparedAtAll() {
    assertFalse(looksLikeAGarbledCopy("123", "124"))
    assertFalse(looksLikeAGarbledCopy("", "07700 900123"))
  }

  @Test
  fun editDistanceCountsInsertionsDeletionsAndSubstitutions() {
    assertTrue(editDistanceAtMostOne("123456", "123457"))
    assertTrue(editDistanceAtMostOne("123456", "1234567"))
    assertTrue(editDistanceAtMostOne("1234567", "123456"))
    assertTrue(editDistanceAtMostOne("123456", "123456"))
    assertFalse(editDistanceAtMostOne("123456", "123478"))
    assertFalse(editDistanceAtMostOne("123456", "12345678"))
  }
}
