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

package com.lin.agent.core.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

  @Test
  fun `nobody has answered anything, so nothing is said`() {
    // The rule the whole file turns on. A prompt that says "answer in English" to somebody
    // who never said so is a prompt making a decision for them and hiding it in text they
    // cannot see.
    assertEquals("", personaPrompt(emptyMap()))
    assertEquals(0, answeredCount(emptyMap()))
  }

  @Test
  fun `a blank answer is not an answer`() {
    assertEquals("", personaPrompt(mapOf("name" to listOf("   "), "about" to listOf(""))))
  }

  @Test
  fun `a name becomes an instruction rather than a fact`() {
    // "The user is called Ben" alone gets ignored by a small model; telling it what to do
    // with that does not.
    val said = personaPrompt(mapOf("name" to listOf("Ben")))
    assertTrue(said, said.contains("Ben"))
    assertTrue(said, said.contains("address them by name"))
  }

  @Test
  fun `each tone says something different`() {
    val tones = Answers.TONE.options.map { personaPrompt(mapOf("tone" to listOf(it))) }
    assertEquals(tones.size, tones.distinct().size)
    assertTrue(tones.none { it.isBlank() })
  }

  @Test
  fun `each length says something different`() {
    val lengths = Answers.LENGTH.options.map { personaPrompt(mapOf("length" to listOf(it))) }
    assertEquals(lengths.size, lengths.distinct().size)
  }

  @Test
  fun `an option nobody offers contributes nothing`() {
    // A value written by a newer build and read by an older one. Better a prompt missing a
    // line than one carrying the word "elaborate" with no sentence around it.
    assertEquals("", personaPrompt(mapOf("tone" to listOf("shakespearean"))))
  }

  @Test
  fun `units reach the prompt as a unit system`() {
    assertTrue(personaPrompt(mapOf("units" to listOf("metric"))).contains("Celsius"))
    assertTrue(personaPrompt(mapOf("units" to listOf("imperial"))).contains("Fahrenheit"))
  }

  @Test
  fun `what they use it for is listed readably`() {
    val said = personaPrompt(mapOf("uses" to listOf("messages", "calendar", "music")))
    assertTrue(said, said.contains("messages and calls, their calendar and reminders and music"))
  }

  @Test
  fun `and what they use it for is never read as a limit`() {
    // A model told "the user asks about music" starts answering music questions to things
    // that were not about music.
    assertTrue(personaPrompt(mapOf("uses" to listOf("music"))).contains("never assume"))
  }

  @Test
  fun `things to ask about first are written so they cannot be read as advice`() {
    val said = personaPrompt(mapOf("ask_first" to listOf("send", "call")))
    assertTrue(said, said.startsWith("Always ask"))
    assertTrue(said, said.contains("even if a setting would allow it"))
  }

  @Test
  fun `a list of three reads as a list of three`() {
    assertEquals("a, b and c", inAList(listOf("a", "b", "c")))
    assertEquals("a and b", inAList(listOf("a", "b")))
    assertEquals("a", inAList(listOf("a")))
    assertEquals("", inAList(emptyList()))
  }

  @Test
  fun `everything answered makes one paragraph with everything in it`() {
    val persona =
      mapOf(
        "name" to listOf("Ben"),
        "tone" to listOf("plain"),
        "length" to listOf("short"),
        "language" to listOf("Hebrew"),
        "place" to listOf("Lisbon"),
        "units" to listOf("metric"),
        "uses" to listOf("messages", "music"),
        "ask_first" to listOf("send"),
        "unsure" to listOf("look_it_up"),
        "big" to listOf("delegate"),
        "about" to listOf("I drive a lot and often ask with my voice."),
      )
    val said = personaPrompt(persona)
    for (expected in listOf("Ben", "Hebrew", "Lisbon", "Celsius", "drive a lot")) {
      assertTrue(expected, said.contains(expected))
    }
    assertEquals(Answers.entries.size, answeredCount(persona))
  }


  @Test
  fun `two tones read as one voice rather than two orders`() {
    // The answer that made TONE a multiple choice: "warm" and "playful" describe the same
    // person. Both have to arrive, and neither may open with the same word, or the prompt
    // reads as a model being given contradictory instructions about how to speak.
    val said = personaPrompt(mapOf("tone" to listOf("warm", "playful")))
    assertTrue(said, said.contains("warm"))
    assertTrue(said, said.contains("humour"))
    assertFalse(said, said.contains("Speak"))
  }

  @Test
  fun `handing big work to the server is hedged and keeping it here is not`() {
    // The tool that does the handing over is withheld when no server is configured, so an
    // absolute instruction to use it would spend turns apologising for something absent.
    val away = personaPrompt(mapOf("big" to listOf("delegate")))
    assertTrue(away, away.contains("if you can"))
    val here = personaPrompt(mapOf("big" to listOf("here")))
    assertTrue(here, here.contains("unless the user asks"))
  }

  // ---- the answers Lin ships with ----

  @Test
  fun `the shipped answers say how Lin behaves, and nothing about who anyone is`() {
    val said = personaPrompt(DEFAULT_PERSONA)
    for (expected in listOf("Celsius", "look it up")) {
      assertTrue(expected, said.contains(expected))
    }
    // No name, place or interests: those are only ever the person's own.
    for (personal in listOf(Answers.NAME, Answers.PLACE, Answers.USES, Answers.ABOUT)) {
      assertFalse(personal.id, DEFAULT_PERSONA.containsKey(personal.id))
    }
    assertFalse(said, said.contains("address them by name"))
  }

  @Test
  fun `the shipped answers leave language and what to ask about first unsaid`() {
    // Both were answered with the absence of an instruction: "whichever I asked in", and
    // "trust the switches". A default sentence for either would quietly override something -
    // the question, or the Tools tab.
    val said = personaPrompt(DEFAULT_PERSONA)
    // The language sentence, not the word "answer": the length answer legitimately says
    // "Answer in a short paragraph", and an assertion that cannot tell them apart would pass
    // for the wrong reason the moment either sentence is reworded.
    assertFalse(said, said.contains("whatever language the question was asked in"))
    assertFalse(said, said.contains("Always ask"))
  }

  @Test
  fun `every shipped answer is a question that exists and an option that is offered`() {
    // A typo here is invisible: the prompt simply comes out missing a line. This is the test
    // that makes it a build failure instead.
    for ((id, values) in DEFAULT_PERSONA) {
      val question = Answers.byId(id)
      assertTrue("no question called $id", question != null)
      val asked = question!!
      if (asked.kind == Answers.Kind.TEXT) {
        assertEquals("$id is text, so one answer", 1, values.size)
        assertTrue("$id is blank", values.first().isNotBlank())
      } else {
        for (value in values) {
          assertTrue("$id=$value is not offered", value in asked.options)
        }
        if (asked.kind == Answers.Kind.ONE_OF) {
          assertEquals("$id takes one answer", 1, values.size)
        }
      }
    }
  }

  @Test
  fun `one answer of your own replaces the shipped set entirely`() {
    // All or nothing on purpose. Somebody who says "call me Sam" and nothing else must not
    // end up with a prompt that also says they are in Lisbon.
    val mine = mapOf("name" to listOf("Sam"))
    assertEquals(mine, personaOrDefault(mine))
    val said = personaPrompt(personaOrDefault(mine))
    assertTrue(said, said.contains("Sam"))
    assertFalse(said, said.contains("Lisbon"))
  }

  @Test
  fun `an untouched quiz still makes Lin personal`() {
    assertEquals(DEFAULT_PERSONA, personaOrDefault(emptyMap()))
    assertTrue(usingDefaultPersona(emptyMap()))
    assertFalse(usingDefaultPersona(mapOf("name" to listOf("Sam"))))
  }

  @Test
  fun `the shipped answers survive the same storage the quiz uses`() {
    // Not hypothetical: clearing the quiz stores nothing, which is what brings these back,
    // and a settings backup round-trips through the same two functions.
    assertEquals(DEFAULT_PERSONA, personaFrom(storedPersona(DEFAULT_PERSONA)))
  }

  // ---- storage ----

  @Test
  fun `answers survive a round trip`() {
    val persona = mapOf("name" to listOf("Ben"), "uses" to listOf("music", "messages"))
    assertEquals(persona, personaFrom(storedPersona(persona)))
  }

  @Test
  fun `nothing stored is nobody answered`() {
    assertEquals(emptyMap<String, List<String>>(), personaFrom(null))
    assertEquals(emptyMap<String, List<String>>(), personaFrom(""))
  }

  @Test
  fun `an unreadable value is nobody answered rather than a crash`() {
    // Read while building the prompt for every turn. A value damaged by a half-finished
    // write should make Lin a little less personal, not stop it answering.
    assertEquals(emptyMap<String, List<String>>(), personaFrom("{not json"))
    assertEquals(emptyMap<String, List<String>>(), personaFrom("[]"))
  }

  @Test
  fun `a key from a newer build is ignored rather than kept`() {
    assertEquals(emptyMap<String, List<String>>(), personaFrom("""{"favourite_colour":"blue"}"""))
  }

  @Test
  fun `blank answers never reach storage`() {
    assertEquals("{}", storedPersona(mapOf("name" to listOf("  "), "uses" to emptyList())))
  }

  @Test
  fun `a text answer stores as text and a multiple as a list`() {
    val stored = storedPersona(mapOf("name" to listOf("Ben"), "uses" to listOf("music")))
    assertTrue(stored, stored.contains("\"name\":\"Ben\""))
    assertTrue(stored, stored.contains("\"uses\":[\"music\"]"))
  }

  @Test
  fun `a stored text answer that arrives as a list still reads`() {
    // Written by an older build, or by a settings import. Taking the first is better than
    // dropping the answer.
    assertEquals(listOf("Ben"), personaFrom("""{"name":["Ben"]}""")["name"])
  }

  @Test
  fun `every question has a distinct id`() {
    val ids = Answers.entries.map { it.id }
    assertEquals(ids.size, ids.distinct().size)
  }

  @Test
  fun `every choice question offers choices and every text one does not`() {
    for (question in Answers.entries) {
      if (question.kind == Answers.Kind.TEXT) {
        assertTrue("${question.id} is text but has options", question.options.isEmpty())
      } else {
        assertTrue("${question.id} offers nothing to pick", question.options.size >= 2)
      }
    }
  }

  @Test
  fun `every offered option produces a sentence`() {
    // An option on the screen that composes nothing is a question somebody answers for no
    // effect, which is worse than not asking.
    for (question in Answers.entries) {
      for (option in question.options) {
        val said = personaPrompt(mapOf(question.id to listOf(option)))
        assertTrue("${question.id}=$option says nothing", said.isNotBlank())
      }
    }
  }
}
