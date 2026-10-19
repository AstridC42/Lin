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

package com.lin.agent.core.training

import com.lin.agent.core.training.TrainingTurn.Role
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the corpus, and what must not get into it.
 *
 * This is training data for somebody's own model, which makes a wrong line worse than a missing
 * one: the format being off makes the whole file unloadable, and a bad example teaches
 * something false and does it quietly. Both failure modes are silent, so both are pinned here.
 */
class TrainingDataTest {

  private fun user(text: String) = TrainingTurn(Role.USER, text)

  private fun assistant(text: String) = TrainingTurn(Role.ASSISTANT, text)

  private val exchange = listOf(user("What time is it?"), assistant("Just gone four."))

  // ---- the format a trainer reads ----

  @Test
  fun aConversationIsOneLineOfMessages() {
    val line = TrainingData.asJsonl(exchange)!!
    assertFalse("A JSONL line cannot contain a newline", line.contains("\n"))

    val messages = JSONObject(line).getJSONArray("messages")
    assertEquals(2, messages.length())
    assertEquals("user", messages.getJSONObject(0).getString("role"))
    assertEquals("What time is it?", messages.getJSONObject(0).getString("content"))
    assertEquals("assistant", messages.getJSONObject(1).getString("role"))
  }

  /** Newlines and quotes in what somebody typed must not break the line they are on. */
  @Test
  fun awkwardTextSurvivesAsContentRatherThanBreakingTheFile() {
    val line = TrainingData.asJsonl(listOf(user("say \"hi\"\nthen bye"), assistant("ok")))!!
    assertFalse(line.contains("\n"))
    assertEquals(
      "say \"hi\"\nthen bye",
      JSONObject(line).getJSONArray("messages").getJSONObject(0).getString("content"),
    )
  }

  /** Tool-use data is the valuable part here, and a result with no name says nothing. */
  @Test
  fun aToolResultKeepsTheNameOfTheToolThatProducedIt() {
    val line =
      TrainingData.asJsonl(
        listOf(
          user("Call Ben"),
          TrainingTurn(Role.TOOL, "The dialer is open.", toolName = "phone_action"),
          assistant("The dialer is open for Ben."),
        )
      )!!
    val tool = JSONObject(line).getJSONArray("messages").getJSONObject(1)
    assertEquals("tool", tool.getString("role"))
    assertEquals("phone_action", tool.getString("name"))
  }

  // ---- what must not get in ----

  /**
   * An unanswered question is what a failed turn leaves behind.
   *
   * Train on it and whatever follows in the file becomes the answer to it. This is the one that
   * would quietly teach the model something false rather than merely waste a line.
   */
  @Test
  fun aQuestionWhoseAnswerFailedIsDropped() {
    val turns = listOf(user("Hello"), assistant("Hello."), user("And the weather?"))
    val kept = TrainingData.usable(turns)
    assertEquals(2, kept.size)
    assertEquals(Role.ASSISTANT, kept.last().role)
  }

  @Test
  fun aTrailingToolResultTheModelNeverUsedIsDropped() {
    val turns =
      listOf(
        user("Find the photo"),
        assistant("Looking."),
        user("And the other one?"),
        TrainingTurn(Role.TOOL, "Found 3.", toolName = "find_photo"),
      )
    assertEquals(2, TrainingData.usable(turns).size)
  }

  /** The trainer applies its own template; a prompt in every example is a prompt being learnt. */
  @Test
  fun theSystemPromptIsNotTrainingContent() {
    val line =
      TrainingData.asJsonl(listOf(TrainingTurn(Role.SYSTEM, "You are Lin."), *exchange.toTypedArray()))!!
    val messages = JSONObject(line).getJSONArray("messages")
    assertEquals(2, messages.length())
    assertFalse(line.contains("You are Lin."))
  }

  @Test
  fun blankTurnsAreDropped() {
    val line = TrainingData.asJsonl(listOf(user("Hi"), assistant("   "), user("Hi?"), assistant("Hello.")))!!
    val messages = JSONObject(line).getJSONArray("messages")
    assertEquals(3, messages.length())
  }

  @Test
  fun somethingOneSidedIsNotAnExampleOfAnything() {
    assertNull(TrainingData.asJsonl(listOf(user("Hello?"))))
    assertNull(TrainingData.asJsonl(listOf(assistant("Hello."))))
    assertNull(TrainingData.asJsonl(emptyList()))
    assertNull(TrainingData.asJsonl(listOf(user(""), assistant(""))))
  }

  // ---- where one conversation ends ----

  /** Nothing in this app ends a conversation, so a gap has to. */
  @Test
  fun aLongGapStartsANewConversation() {
    val now = 1_000_000_000L
    assertTrue(TrainingData.startsNewConversation(now - TrainingData.NEW_CONVERSATION_GAP_MS, now))
    assertTrue(TrainingData.startsNewConversation(now - 60 * 60 * 1000L, now))
  }

  @Test
  fun comingStraightBackContinuesTheSameConversation() {
    val now = 1_000_000_000L
    assertFalse(TrainingData.startsNewConversation(now - 1000L, now))
    assertFalse(TrainingData.startsNewConversation(now - 29 * 60 * 1000L, now))
  }

  @Test
  fun theFirstEverMessageStartsAConversation() {
    assertTrue(TrainingData.startsNewConversation(0L, 1_000L))
  }

  // ---- the files ----

  @Test
  fun packsSortInTheOrderTheyWereMade() {
    val day = 1_760_000_000_000L
    val names = listOf(1, 2, 10, 11).map { TrainingData.packName(it, day) }
    assertEquals(names, names.sorted())
  }

  @Test
  fun aPackIsNamedForItsNumberAndItsDay() {
    val name = TrainingData.packName(7, 1_760_000_000_000L)
    assertTrue(name, name.startsWith("lin-0007-"))
    assertTrue(name, name.endsWith(".jsonl"))
  }

  @Test
  fun aPacksNumberCanBeReadBackOutOfItsName() {
    val name = TrainingData.packName(42, 1_760_000_000_000L)
    assertEquals(42, TrainingData.sequenceOf(name))
  }

  @Test
  fun somethingElseInTheDirectoryIsNotMistakenForAPack() {
    assertNull(TrainingData.sequenceOf("notes.txt"))
    assertNull(TrainingData.sequenceOf("lin-7-2026-10-13.jsonl"))
    assertNull(TrainingData.sequenceOf("lin-0007-2026-10-13.json"))
  }

  @Test
  fun thirtyIsWhatWasAskedFor() {
    assertEquals(30, TrainingData.PACK_SIZE)
  }
}
