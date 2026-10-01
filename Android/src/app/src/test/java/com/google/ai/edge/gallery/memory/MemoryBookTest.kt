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

package com.google.ai.edge.gallery.memory

import com.google.ai.edge.gallery.proto.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules about what the assistant keeps.
 *
 * Two pressures meet here and the tests are mostly about them. Every fact is charged against the
 * same few thousand tokens as the tools and the conversation, so the list has to stay bounded
 * whatever the model does with it. And a fact remembered wrongly is repeated with complete
 * confidence until somebody notices, so duplicates must not pile up and a deletion must never take
 * the wrong line.
 */
class MemoryBookTest {

  private val now = 1_700_000_000_000L
  private var counter = 0

  private fun ids(): () -> String = { "id-${counter++}" }

  private fun memoryOf(vararg texts: String): AssistantMemory {
    var memory = AssistantMemory.getDefaultInstance()
    for (text in texts) {
      memory = MemoryBook.remember(memory, text, now, ids())!!
    }
    return memory
  }

  @Test
  fun keepsWhatItIsTold() {
    val memory = MemoryBook.remember(AssistantMemory.getDefaultInstance(), "takes the train", now, ids())
    assertNotNull(memory)
    assertEquals(1, memory!!.factCount)
    assertEquals("takes the train", memory.getFact(0).text)
    assertEquals(now, memory.getFact(0).savedAtMillis)
  }

  /** Newest first, so the prompt leads with what was learned most recently. */
  @Test
  fun putsTheNewestFirst() {
    val memory = memoryOf("first", "second")
    assertEquals(listOf("second", "first"), memory.factList.map { it.text })
  }

  /**
   * A model asked twice about the same preference saves it twice. Two copies spend the budget
   * twice to say one thing.
   */
  @Test
  fun replacesAFactThatSaysTheSameThing() {
    val memory = memoryOf("Takes the train.", "takes the train")
    assertEquals(1, memory.factCount)
  }

  /** Same wording, different punctuation and spacing, is the same fact. */
  @Test
  fun ignoresPunctuationAndSpacingWhenComparing() {
    val memory = memoryOf("calls his mother Ima", "  calls his   mother Ima!  ")
    assertEquals(1, memory.factCount)
  }

  /** Facts that differ in a way that matters are both kept. */
  @Test
  fun keepsFactsThatActuallyDiffer() {
    val memory = memoryOf("takes the train", "does not drive")
    assertEquals(2, memory.factCount)
  }

  /** The cap is the whole defence of the token budget, so it holds however much is pushed in. */
  @Test
  fun neverGrowsPastTheCap() {
    var memory = AssistantMemory.getDefaultInstance()
    for (i in 1..MemoryBook.MAX_FACTS * 3) {
      memory = MemoryBook.remember(memory, "fact number $i", now, ids())!!
    }
    assertEquals(MemoryBook.MAX_FACTS, memory.factCount)
    // The newest survived and the oldest fell off.
    assertEquals("fact number ${MemoryBook.MAX_FACTS * 3}", memory.getFact(0).text)
    assertTrue(memory.factList.none { it.text == "fact number 1" })
  }

  /** One fact cannot become a paragraph that crowds out the conversation. */
  @Test
  fun trimsAFactThatRunsOn() {
    val long = "a".repeat(MemoryBook.MAX_FACT_CHARS * 3)
    val memory = MemoryBook.remember(AssistantMemory.getDefaultInstance(), long, now, ids())
    assertEquals(MemoryBook.MAX_FACT_CHARS, memory!!.getFact(0).text.length)
  }

  /** Nothing worth saving is reported as such rather than stored as an empty line. */
  @Test
  fun refusesToRememberNothing() {
    assertNull(MemoryBook.remember(AssistantMemory.getDefaultInstance(), "   ", now, ids()))
    assertNull(MemoryBook.remember(AssistantMemory.getDefaultInstance(), "", now, ids()))
  }

  @Test
  fun forgetsById() {
    val memory = memoryOf("takes the train", "does not drive")
    val id = memory.getFact(0).id
    val updated = MemoryBook.forget(memory, id)
    assertNotNull(updated)
    assertEquals(1, updated!!.factCount)
    assertTrue(updated.factList.none { it.id == id })
  }

  @Test
  fun reportsAnIdItDoesNotHave() {
    assertNull(MemoryBook.forget(memoryOf("takes the train"), "nonsense"))
  }

  /** "forget that I take the train" is how a person asks; they do not have an id. */
  @Test
  fun forgetsBySomethingLikeTheWording() {
    val memory = memoryOf("takes the train to work", "allergic to peanuts")
    val updated = MemoryBook.forgetMatching(memory, "takes the train")
    assertNotNull(updated)
    assertEquals(listOf("allergic to peanuts"), updated!!.factList.map { it.text })
  }

  /**
   * Two candidates and nothing is removed. Erasing the wrong memory is not something the user can
   * see has happened, so an ambiguous instruction has to come back as a question.
   */
  @Test
  fun refusesToGuessBetweenTwoMatches() {
    val memory = memoryOf("takes the train to work", "takes the train on Sundays")
    assertNull(MemoryBook.forgetMatching(memory, "takes the train"))
  }

  @Test
  fun refusesToForgetSomethingThatMatchesNothing() {
    assertNull(MemoryBook.forgetMatching(memoryOf("takes the train"), "plays the cello"))
    assertNull(MemoryBook.forgetMatching(memoryOf("takes the train"), "  "))
  }

  @Test
  fun formatsOneLinePerFact() {
    assertEquals("- second\n- first", MemoryBook.format(memoryOf("first", "second")))
  }

  /**
   * Empty, not a heading with nothing under it: the caller leaves the whole section out, rather
   * than spending tokens telling the model it knows nothing about you.
   */
  @Test
  fun formatsNothingAsNothing() {
    assertEquals("", MemoryBook.format(AssistantMemory.getDefaultInstance()))
  }
}
