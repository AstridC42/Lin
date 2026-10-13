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

package com.lin.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter between a model's idea of what is memorable and what actually gets written down.
 *
 * Almost all of this is about one failure. Ask a 2B model what is worth remembering and it
 * answers "the user said hello" and "the user asked about the weather" - facts about the
 * conversation, not about the person. A store full of those is worse than an empty one: they
 * crowd out the real entries in every search and make the memory screen unreadable. The prompt
 * asks for the right thing; this enforces it, because a prompt is a request and a filter is a
 * rule.
 */
class DigestTest {

  // ---- what gets thrown away ----

  @Test
  fun aSummaryOfTheConversationIsNotAMemory() {
    assertFalse(Digest.worthKeeping("The user asked about the weather in Lisbon."))
    assertFalse(Digest.worthKeeping("The user wanted to know the time."))
    assertFalse(Digest.worthKeeping("We discussed hotels for a while."))
    assertFalse(Digest.worthKeeping("The conversation was about trains."))
    assertFalse(Digest.worthKeeping("The user said hello and asked how I was."))
  }

  @Test
  fun whatTheAssistantDidIsNotAMemoryEither() {
    assertFalse(Digest.worthKeeping("I helped them draft an email."))
    assertFalse(Digest.worthKeeping("Lin called Ben Elbit for them."))
    assertFalse(Digest.worthKeeping("The assistant opened the dialer."))
  }

  /** A model with nothing to say sometimes says so in the list rather than leaving it empty. */
  @Test
  fun theModelSayingItFoundNothingIsNotStoredAsAFact() {
    assertFalse(Digest.worthKeeping("No lasting facts were mentioned."))
    assertFalse(Digest.worthKeeping("Nothing lasting came up in this conversation."))
  }

  @Test
  fun aQuestionIsNotAFact() {
    assertFalse(Digest.worthKeeping("Does the user prefer trains or planes?"))
  }

  @Test
  fun somethingTooShortToStandAloneIsRejected() {
    assertFalse(Digest.worthKeeping(""))
    assertFalse(Digest.worthKeeping("Otto"))
    assertFalse(Digest.worthKeeping("likes cats"))
  }

  /** A paragraph is a summary wearing a fact's clothes. */
  @Test
  fun somethingTooLongIsRejected() {
    assertFalse(Digest.worthKeeping("x".repeat(250)))
  }

  // ---- what survives ----

  @Test
  fun aDurableFactAboutThePersonIsKept() {
    assertTrue(Digest.worthKeeping("Otto's sister's birthday is the 3rd of March."))
    assertTrue(Digest.worthKeeping("Otto prefers trains to flying for short trips."))
    assertTrue(Digest.worthKeeping("Ben Elbit is Otto's colleague."))
  }

  /**
   * "Asked" is only disqualifying at the start of the sentence.
   *
   * "Otto asked me to call Ben every Friday" is a standing commitment; "The user asked about
   * trains" is a minute of somebody's afternoon. The difference is where the phrase sits,
   * which is why the rejections are prefixes rather than substrings.
   */
  @Test
  fun aStandingRequestIsKeptEvenThoughItContainsAsked() {
    assertTrue(Digest.worthKeeping("Otto asked to be reminded about the dentist every March."))
  }

  // ---- reading the model's answer ----

  @Test
  fun factsAreReadAndFiltered() {
    val facts =
      Digest.parse(
        """
        {"facts":[
          "Otto's sister is called Mia.",
          "The user asked about the weather.",
          "Otto works with Ben Elbit."
        ]}
        """
          .trimIndent()
      )
    assertEquals(listOf("Otto's sister is called Mia.", "Otto works with Ben Elbit."), facts)
  }

  @Test
  fun theSameFactTwiceIsStoredOnce() {
    val facts =
      Digest.parse(
        """{"facts":["Otto prefers trains.","otto prefers trains.","Mia is his sister."]}"""
      )
    assertEquals(2, facts.size)
  }

  /** A ceiling per conversation, so one long evening cannot flood the store. */
  @Test
  fun noMoreThanTheCapIsKeptFromOneConversation() {
    val many = (1..20).joinToString(",") { "\"Otto likes thing number $it very much.\"" }
    assertEquals(Digest.MAX_FACTS, Digest.parse("""{"facts":[$many]}""").size)
  }

  @Test
  fun anEmptyListIsAnEmptyList() {
    assertTrue(Digest.parse("""{"facts":[]}""").isEmpty())
  }

  @Test
  fun somethingThatIsNotTheExpectedShapeYieldsNothingRatherThanAGuess() {
    assertTrue(Digest.parse("I did not find anything worth remembering.").isEmpty())
    assertTrue(Digest.parse("{}").isEmpty())
    assertTrue(Digest.parse("").isEmpty())
  }

  // ---- the schema the decoder is held to ----

  @Test
  fun theSchemaRequiresTheFactsFieldSoAnEmptyAnswerIsStillAnAnswer() {
    val required = org.json.JSONObject(Digest.SCHEMA).getJSONArray("required")
    assertEquals("facts", required.getString(0))
  }

  /** The prompt has to say what not to write; left alone the model summarises. */
  @Test
  fun thePromptForbidsRecordingTheConversationItself() {
    assertTrue(Digest.PROMPT, Digest.PROMPT.contains("Do not record what was discussed"))
  }
}
