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

import com.google.ai.edge.gallery.tools.DeviceActionsTool.Companion.cleanContactQuery
import com.google.ai.edge.gallery.tools.DeviceActionsTool.Companion.contactQueryCandidates
import com.google.ai.edge.gallery.tools.DeviceActionsTool.Companion.rankAppMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two matchers behind "it sometimes will not open an app or find the person I tell it to call".
 *
 * Both take what a language model produced and have to reach something on the device. The model
 * does not restrict itself to the bare name, so anything that assumes it did fails on input that
 * looks perfectly correct to the person who typed it.
 */
class DeviceActionsMatchingTest {

  // ---- Contact query cleaning -------------------------------------------------------------

  @Test
  fun stripsLeadingVerbsThatAreNotPartOfAnyName() {
    assertEquals("Sam", cleanContactQuery("call Sam"))
    assertEquals("Sam", cleanContactQuery("Call Sam"))
    assertEquals("Sam", cleanContactQuery("text Sam"))
    assertEquals("Sam", cleanContactQuery("dial Sam"))
    assertEquals("Sam", cleanContactQuery("please call Sam"))
  }

  @Test
  fun stripsThePossessive() {
    assertEquals("Sam", cleanContactQuery("Sam's"))
    assertEquals("Sam", cleanContactQuery("Sam’s"))
    assertEquals("Sam", cleanContactQuery("call Sam's number"))
  }

  @Test
  fun stripsSurroundingPunctuationAndQuotes() {
    assertEquals("Sam", cleanContactQuery("\"Sam\""))
    assertEquals("Sam", cleanContactQuery("Sam."))
    assertEquals("Sam Smith", cleanContactQuery("  Sam Smith  "))
  }

  /** Only the edges are trimmed - plenty of real contacts have these words inside the name. */
  @Test
  fun keepsNoiseWordsInsideAName() {
    assertEquals("Bank of the West", cleanContactQuery("Bank of the West"))
  }

  /**
   * "call Sam" and "Call of Duty Support" are indistinguishable by inspection, so both readings
   * are offered and the search tries each. Stripping is first because it is the common case; the
   * literal string has to remain reachable or a contact whose name starts with one of those words
   * can never be found.
   */
  @Test
  fun offersBothTheStrippedAndTheLiteralReading() {
    assertEquals(listOf("Sam", "call Sam"), contactQueryCandidates("call Sam"))
    assertEquals(
      listOf("of Duty Support", "Call of Duty Support"),
      contactQueryCandidates("Call of Duty Support"),
    )
  }

  /** A name needing no cleaning is one query, not the same query twice. */
  @Test
  fun offersASingleReadingWhenThereIsNothingToStrip() {
    assertEquals(listOf("Sam Smith"), contactQueryCandidates("Sam Smith"))
    assertEquals(listOf("mum"), contactQueryCandidates("mum"))
  }

  @Test
  fun offersNothingForABlankRequest() {
    assertEquals(listOf<String>(), contactQueryCandidates("   "))
  }

  @Test
  fun leavesAnOrdinaryNameAlone() {
    assertEquals("Sam Smith", cleanContactQuery("Sam Smith"))
    assertEquals("mum", cleanContactQuery("mum"))
  }

  /** Nothing but noise still has to search for something rather than for the empty string. */
  @Test
  fun fallsBackWhenEverythingLooksLikeNoise() {
    assertEquals("call", cleanContactQuery("call"))
  }

  // ---- App ranking ------------------------------------------------------------------------

  private val installed =
    listOf("Google Chrome", "Google Play Store", "Google Play Games", "Gmail", "Waze", "Messages")

  private fun best(request: String): String? =
    rankAppMatches(request, installed).firstOrNull()?.let { installed[it.index] }

  @Test
  fun findsAnAppByItsExactName() {
    assertEquals("Waze", best("Waze"))
    assertEquals("Gmail", best("gmail"))
  }

  /** The failing case: extra words meant the whole request matched nothing. */
  @Test
  fun findsAnAppDespiteExtraWordsAroundTheName() {
    assertEquals("Gmail", best("open my Gmail"))
    assertEquals("Waze", best("launch the Waze app"))
    assertEquals("Google Chrome", best("please open Chrome"))
  }

  @Test
  fun findsAnAppByPartOfItsName() {
    assertEquals("Google Chrome", best("chrome"))
    assertEquals("Gmail", best("mail"))
  }

  /** The other failing case: two equally good fits used to be settled by list order. */
  @Test
  fun reportsATieRatherThanGuessing() {
    val ranked = rankAppMatches("play", installed)
    assertTrue("expected at least two candidates for 'play'", ranked.size >= 2)
    assertEquals(
      "both Play apps should score identically so the caller has to ask",
      ranked[0].score,
      ranked[1].score,
    )
  }

  /** A clear winner must not be reported as a tie, or every request becomes a question. */
  @Test
  fun doesNotReportATieWhenOneAppClearlyWins() {
    val ranked = rankAppMatches("waze", installed)
    assertTrue(ranked.isNotEmpty())
    assertTrue(
      "Waze should outrank anything else",
      ranked.size == 1 || ranked[0].score > ranked[1].score,
    )
  }

  @Test
  fun returnsNothingForAnAppThatIsNotInstalled()  {
    assertEquals(listOf<DeviceActionsTool.Companion.AppMatch>(), rankAppMatches("Spotify", installed))
  }

  @Test
  fun returnsNothingForAnEmptyRequest() {
    assertEquals(listOf<DeviceActionsTool.Companion.AppMatch>(), rankAppMatches("   ", installed))
  }
}
