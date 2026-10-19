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

package com.lin.agent.core.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finding the document somebody meant.
 *
 * The query is a name said out loud and relayed by a model, so it is never the file name. The
 * cost of getting this wrong is Lin reading out the wrong contract, which is why the ordering
 * is pinned rather than left to whatever the list happens to be sorted by.
 */
class DocumentLibraryTest {

  private var clock = 0L

  private fun doc(name: String) = KnownDoc(uri = "content://$name", name = name, addedAt = ++clock)

  private val rent = doc("Rent agreement (2026).docx")
  private val notes = doc("Notes.txt")
  private val meeting = doc("Meeting notes, January.docx")
  private val library = listOf(rent, notes, meeting)

  @Test
  fun theFullNameFinndsIt() {
    assertEquals(rent, matching(library, "Rent agreement (2026).docx"))
  }

  @Test
  fun theNameWithoutItsExtensionFindsIt() {
    assertEquals(rent, matching(library, "rent agreement (2026)"))
  }

  /** Exact beats everything, even when a later document contains the word too. */
  @Test
  fun anExactNameWinsOverSomethingMoreRecentThatMerelyContainsIt() {
    assertEquals(notes, matching(library, "notes"))
  }

  @Test
  fun aPrefixIsEnough() {
    assertEquals(rent, matching(library, "rent"))
  }

  @Test
  fun somethingInTheMiddleOfTheNameIsEnough() {
    assertEquals(meeting, matching(library, "january"))
  }

  /** "the rent agreement" is how somebody actually asks. */
  @Test
  fun fillerWordsAroundTheNameDoNotStopIt() {
    assertEquals(rent, matching(library, "the rent agreement"))
  }

  @Test
  fun theMostRecentBreaksATie() {
    val older = KnownDoc("content://a", "Report.docx", addedAt = 1)
    val newer = KnownDoc("content://b", "Report.docx", addedAt = 2)
    assertEquals(newer, matching(listOf(older, newer), "report"))
  }

  // ---- when it should give up ----

  /**
   * Nothing rather than a guess.
   *
   * A wrong document read confidently is worse than "I cannot find that": the user has no way
   * to tell that the answer came from the wrong file.
   */
  @Test
  fun somethingThatMatchesNothingFindsNothing() {
    assertNull(matching(library, "tax return"))
    assertNull(matching(library, "zzz"))
  }

  @Test
  fun anEmptyQueryOrAnEmptyLibraryFindsNothing() {
    assertNull(matching(library, ""))
    assertNull(matching(library, "   "))
    assertNull(matching(emptyList(), "notes"))
  }

  /** Short filler on its own must not match on word overlap, or "the" finds anything. */
  @Test
  fun fillerAloneIsNotAMatch() {
    assertNull(matching(listOf(doc("The Report.docx")), "the"))
    assertNull(matching(listOf(doc("A Letter.docx")), "a"))
  }
}
