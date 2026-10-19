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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model going round in circles, which was watched happening on a phone.
 *
 * Asked "play something" with a `play_music` whose `what` was required, Gemma 4 12B wrote the
 * same paragraph of reasoning over and over for as long as anybody watched. Nothing was
 * counting, so nothing stopped it.
 */
class RunawayTest {

  /** The actual paragraph from the screenshot, shortened but with its shape. */
  private val deliberation =
    "The user wants to \"Play something\". I should check what is currently playing or what " +
      "music is available. The play_music tool requires a what parameter. Since the user did " +
      "not specify what to play, I should first check what is currently playing using media. "

  @Test
  fun `a paragraph written three times is a rut`() {
    assertTrue(looksLikeARut(deliberation.repeat(3)))
  }

  @Test
  fun `the same paragraph twice is not`() {
    // Twice could be a refrain, a quotation, or a model restating a question before
    // answering it. Cutting a model off for that would be worse than the rut.
    assertFalse(looksLikeARut(deliberation.repeat(2)))
  }

  @Test
  fun `ordinary prose of the same length is not a rut`() {
    val essay =
      (1..60).joinToString(" ") {
        "Sentence number $it carries its own meaning, built from words chosen for it alone " +
          "rather than copied from the sentence before."
      }
    assertFalse(essay.length.toString(), looksLikeARut(essay))
  }

  @Test
  fun `a short repetition is left alone`() {
    // A model saying "no, no, no" is emphatic, not stuck. What keeps it safe is the overall
    // length floor rather than the size of the repeating block.
    assertFalse(looksLikeARut("no, ".repeat(20)))
  }

  @Test
  fun `a short phrase repeated for hundreds of characters is a rut`() {
    // The gap the first version left. A model emitting "no. " until the server's own limit
    // is every bit as stuck as one repeating a paragraph, and requiring a long repeating
    // block let it through.
    assertTrue(looksLikeARut("no. ".repeat(200)))
  }

  @Test
  fun `a short phrase repeated enough is still a rut`() {
    // The repeating unit is whatever the model got stuck on, which is sometimes a sentence
    // rather than a paragraph.
    assertTrue(looksLikeARut("I should check what is currently playing. ".repeat(40)))
  }

  @Test
  fun `a numbered list whose items share a stem is not a rut`() {
    // The false positive that made the first version of this unusable. Items built from a
    // template repeat a long stretch of text over and over - and are perfectly ordinary
    // output. What tells them apart from a rut is that there is different content between
    // the repeats.
    val list =
      (1..40).joinToString("\n") {
        "$it. The thing to do here is to check the value and then report it back clearly."
      }
    assertFalse(list, looksLikeARut(list))
  }

  @Test
  fun `a table of similar rows is not a rut`() {
    val table = (1..40).joinToString("\n") { "| row $it | yes | no | pending | see the notes |" }
    assertFalse(table, looksLikeARut(table))
  }

  @Test
  fun `a list that repeats a header far apart is not a rut`() {
    // Measured against the tail rather than the whole text, so a long document that reuses a
    // heading a hundred lines apart is not mistaken for a model in a rut.
    val filler = (1..200).joinToString("\n") { "Line $it with its own distinct content here." }
    assertFalse(looksLikeARut("## Heading\n$filler\n## Heading\n$filler\n## Heading\n$filler"))
  }

  @Test
  fun `nothing yet is not a rut`() {
    assertFalse(looksLikeARut(""))
    assertFalse(looksLikeARut("Hello."))
  }

  @Test
  fun `the note tells the model to stop thinking and not to mention it`() {
    assertTrue(RUNAWAY_NUDGE, RUNAWAY_NUDGE.contains("Do not think any further"))
    assertTrue(RUNAWAY_NUDGE, RUNAWAY_NUDGE.contains("Do not mention this note"))
  }

  @Test
  fun `what the user is shown says what to do about it`() {
    assertTrue(STUCK, STUCK.contains("specifically"))
  }

  // ---- the loop ----

  @Test
  fun `a model that goes round is cut off and told to answer`() = runTest {
    val backend = Looping(thenAnswer = "Nothing is loaded - what would you like?")
    val events =
      TurnLoop(backend, ToolRegistry(emptyList()), AgentRole.ORCHESTRATOR)
        .run(listOf(Message(Message.Role.USER, "play something")))
        .toList()

    assertEquals(LoopEvent.Done("Nothing is loaded - what would you like?"), events.last())
    // The second exchange carries the note, which is the whole point of cutting it off.
    assertTrue(backend.seen[1].messages.last().text, backend.seen[1].messages.last().text.contains("repeating yourself"))
  }

  @Test
  fun `the stream is abandoned rather than read to the end`() = runTest {
    val backend = Looping(thenAnswer = "ok")
    TurnLoop(backend, ToolRegistry(emptyList()), AgentRole.ORCHESTRATOR)
      .run(listOf(Message(Message.Role.USER, "play something")))
      .toList()

    // A server in this state generates until its own limit, which on an unconfigured one is
    // a very long way away. Reading it all and then noticing would be no fix at all.
    assertTrue("${backend.emitted}", backend.emitted < Looping.FOREVER)
  }

  @Test
  fun `a model that goes round twice is given up on rather than nudged again`() = runTest {
    val backend = Looping(thenAnswer = null)
    val events =
      TurnLoop(backend, ToolRegistry(emptyList()), AgentRole.ORCHESTRATOR)
        .run(listOf(Message(Message.Role.USER, "play something")))
        .toList()

    val failed = events.last() as LoopEvent.Failed
    assertEquals(STUCK, failed.message)
  }

  /** Thinks in circles; on the second exchange answers, or loops again if there is no answer. */
  private class Looping(private val thenAnswer: String?) : LlmBackend {
    val seen = mutableListOf<TurnRequest>()
    var emitted = 0
      private set

    override val id = "looping"
    override val capabilities = BackendCapabilities(true, false, false, 8192)

    override suspend fun reachable() = true

    override fun converse(request: TurnRequest): Flow<TurnEvent> = flow {
      val exchange = seen.size
      seen.add(request)
      if (exchange == 1 && thenAnswer != null) {
        emit(TurnEvent.Done(thenAnswer))
        return@flow
      }
      repeat(FOREVER) {
        emitted++
        emit(
          TurnEvent.Thought(
            "The user wants to \"Play something\". I should check what is currently playing " +
              "or what music is available. The play_music tool requires a what parameter. "
          )
        )
      }
      emit(TurnEvent.Done("never reached"))
    }

    companion object {
      /** Stands in for "until the server's own limit", which is effectively unbounded. */
      const val FOREVER = 400
    }
  }
}
