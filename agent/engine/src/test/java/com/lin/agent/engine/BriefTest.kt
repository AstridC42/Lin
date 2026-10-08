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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delegation payload, which the plan calls the highest-risk component and it is.
 *
 * Delegation means a 2B model restating a request for a model that cannot see the conversation,
 * and the characteristic failure of every multi-agent system is that the restatement quietly
 * drops the one condition that mattered. Most of these tests are about that condition surviving.
 */
class BriefTest {

  @Test
  fun aFilledBriefIsRead() {
    val result =
      Brief.parse(
        """
        {"goal":"Find a hotel in Lisbon",
         "constraints":["under 120 euros a night","walkable to the old town"],
         "expects":"LIST"}
        """
          .trimIndent()
      )
    val brief = (result as BriefResult.Usable).brief
    assertEquals("Find a hotel in Lisbon", brief.goal)
    assertEquals(2, brief.constraints.size)
    assertEquals(OutputShape.LIST, brief.expects)
  }

  /** No goal is not a task, and must never be sent anywhere. */
  @Test
  fun aBriefWithNoGoalIsUnusable() {
    assertTrue(Brief.parse("""{"constraints":["cheap"]}""") is BriefResult.Unusable)
    assertTrue(Brief.parse("""{"goal":"   "}""") is BriefResult.Unusable)
  }

  @Test
  fun somethingThatIsNotJsonIsUnusableRatherThanGuessedAt() {
    val result = Brief.parse("Find a hotel in Lisbon, under 120 a night")
    assertTrue(result is BriefResult.Unusable)
  }

  /**
   * A malformed constraint loses that constraint, not the task.
   *
   * The right asymmetry: dropping one condition is bad, and failing the whole delegation because
   * one array entry was empty is worse - the user gets nothing at all for a typo in a slot.
   */
  @Test
  fun anEmptyConstraintIsDroppedWithoutLosingTheRest() {
    val brief =
      (Brief.parse("""{"goal":"g","constraints":["under 50","","  ","in French"]}""")
          as BriefResult.Usable)
        .brief
    assertEquals(listOf("under 50", "in French"), brief.constraints)
  }

  /** An unrecognised shape costs formatting, not the task, so it falls back rather than fails. */
  @Test
  fun anUnknownOutputShapeFallsBackInsteadOfFailing() {
    val brief =
      (Brief.parse("""{"goal":"g","expects":"HAIKU"}""") as BriefResult.Usable).brief
    assertEquals(OutputShape.SHORT_ANSWER, brief.expects)
  }

  @Test
  fun theShapeIsReadWhateverCaseTheModelUsed() {
    assertEquals(OutputShape.DRAFT_TEXT, OutputShape.from("draft_text"))
    assertEquals(OutputShape.PLAN, OutputShape.from(" Plan "))
  }

  // ---- what the sub-agent is actually told ----

  /**
   * Constraints appear as their own block, called binding, not folded into the task sentence.
   *
   * This is the whole mechanism. A sub-agent reading one paragraph treats "under 120 euros" as
   * flavour; a list under "these conditions are binding" is much harder to skim past.
   */
  @Test
  fun theInstructionMakesConstraintsBindingRatherThanDecorative() {
    val instruction =
      Brief(goal = "Find a hotel in Lisbon", constraints = listOf("under 120 euros a night"))
        .asInstruction()

    assertTrue(instruction, instruction.contains("Find a hotel in Lisbon"))
    assertTrue(instruction, instruction.contains("binding"))
    assertTrue(instruction, instruction.contains("- under 120 euros a night"))
    assertTrue("a broken condition has to read as wrong", instruction.contains("is wrong"))
  }

  @Test
  fun anUnconstrainedTaskSaysNothingAboutConditions() {
    val instruction = Brief(goal = "Say hello").asInstruction()
    assertTrue(instruction, !instruction.contains("binding"))
  }

  @Test
  fun eachShapeAsksForSomethingDifferent() {
    val asked = OutputShape.entries.map { Brief(goal = "g", expects = it).asInstruction() }
    assertEquals("each shape should read differently", asked.size, asked.toSet().size)
  }

  // ---- the schema the decoder is held to ----

  /**
   * Constraints are required even when empty.
   *
   * Under constrained decoding an optional field is one the decoder may simply not emit, and the
   * field being absent is indistinguishable from there being no conditions. Required-but-empty
   * forces the question to be answered.
   */
  @Test
  fun theSchemaRequiresTheConstraintsFieldEvenWhenThereAreNone() {
    val required = JSONObject(Brief.SCHEMA).getJSONArray("required")
    val names = (0 until required.length()).map { required.getString(it) }
    assertTrue(names.toString(), names.containsAll(listOf("goal", "constraints", "expects")))
  }

  @Test
  fun theSchemaOffersExactlyTheShapesThatCanBeParsedBack() {
    val enum =
      JSONObject(Brief.SCHEMA).getJSONObject("properties").getJSONObject("expects").getJSONArray("enum")
    val offered = (0 until enum.length()).map { enum.getString(it) }.toSet()
    assertEquals(OutputShape.entries.map { it.name }.toSet(), offered)
    // And every one of them survives the round trip the model's answer will take.
    offered.forEach { assertTrue(it, OutputShape.from(it) != null) }
  }

  @Test
  fun theSchemaIsValidJson() {
    assertEquals("object", JSONObject(Brief.SCHEMA).getString("type"))
  }
}
