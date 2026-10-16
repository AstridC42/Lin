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
        "place" to listOf("Tel Aviv"),
        "units" to listOf("metric"),
        "uses" to listOf("messages", "music"),
        "ask_first" to listOf("send"),
        "unsure" to listOf("look_it_up"),
        "about" to listOf("I drive a lot and often ask with my voice."),
      )
    val said = personaPrompt(persona)
    for (expected in listOf("Ben", "Hebrew", "Tel Aviv", "Celsius", "drive a lot")) {
      assertTrue(expected, said.contains(expected))
    }
    assertEquals(10, answeredCount(persona))
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
