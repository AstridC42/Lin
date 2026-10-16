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

import org.json.JSONArray
import org.json.JSONObject

/**
 * The questions that make Lin somebody's rather than anybody's.
 *
 * A system prompt written by hand is a system prompt that is wrong about the person using it
 * - it cannot know what to call them, which language to answer in, or whether they want a
 * sentence or three paragraphs. Asking is both more accurate and cheaper than guessing, and a
 * short questionnaire asked once is a far better interface for it than a free-text box
 * labelled "system prompt", which most people would leave empty and the rest would fill with
 * something that fights the capability half.
 *
 * What is **not** here matters as much. Nothing asks the person to describe Lin's abilities:
 * that half of the prompt is built from the tools actually switched on - see
 * [com.lin.agent.engine.capabilityPrompt] - so it cannot claim something Lin cannot do or
 * miss something it can. A prompt somebody typed in January describing the tools of January
 * is the drift this design exists to prevent.
 *
 * Ids rather than text, because the questions are shown through Android string resources and
 * so can be translated, while the prompt they compose is read by a model and is written here.
 */
enum class Answers(val id: String, val kind: Kind, val options: List<String> = emptyList()) {
  /** What to call them. A name, or empty for nothing in particular. */
  NAME("name", Kind.TEXT),

  /**
   * How Lin should sound.
   *
   * More than one, because the answers are not alternatives: "warm" and "playful" describe the
   * same voice, and so do "plain" and "precise". Forcing a single pick threw away half of what
   * the first person to answer it told us.
   */
  TONE("tone", Kind.ANY_OF, listOf("plain", "warm", "precise", "playful")),

  /** How much Lin should say when it has a choice. */
  LENGTH("length", Kind.ONE_OF, listOf("short", "medium", "full")),

  /** Which language to answer in. Empty means whichever the person used. */
  LANGUAGE("language", Kind.TEXT),

  /** Roughly where they are, for dates, times and what "nearby" means. */
  PLACE("place", Kind.TEXT),

  /** Metric or imperial. */
  UNITS("units", Kind.ONE_OF, listOf("metric", "imperial")),

  /** What they mostly use Lin for, so it reaches for the right thing first. */
  USES(
    "uses",
    Kind.ANY_OF,
    listOf("messages", "calendar", "music", "lookups", "memory", "phone", "writing"),
  ),

  /** What Lin must always ask about first, whatever a setting says. */
  ASK_FIRST("ask_first", Kind.ANY_OF, listOf("send", "call", "settings", "play", "open")),

  /** What to do when it does not know. */
  UNSURE("unsure", Kind.ONE_OF, listOf("say_so", "look_it_up")),

  /** What to do with work the phone's own model is too small for. */
  BIG("big", Kind.ONE_OF, listOf("here", "delegate")),

  /** Anything else worth always knowing. Free text. */
  ABOUT("about", Kind.TEXT);

  enum class Kind {
    TEXT,
    ONE_OF,
    ANY_OF,
  }

  companion object {
    fun byId(id: String): Answers? = entries.firstOrNull { it.id == id }
  }
}

/** What somebody answered, by question id. A text answer is a single-item list. */
typealias Persona = Map<String, List<String>>

/**
 * The personal half of the system prompt.
 *
 * Only what was answered. An unanswered question contributes nothing at all rather than a
 * default sentence, and that is the rule the whole function turns on: a prompt that says
 * "answer in English" to somebody who never said so is a prompt making a decision on their
 * behalf and then hiding it in text they cannot see. Empty in, empty out.
 *
 * Written as instructions to the model rather than as a description of the person, because
 * the first is what a small model follows and the second is what it ignores.
 */
fun personaPrompt(persona: Persona): String {
  val said = mutableListOf<String>()

  first(persona, Answers.NAME)?.let { said.add("The user is called $it; address them by name.") }

  // Worded so that two of them read as one voice rather than as two orders: each is a clause
  // about how to speak, none of them opens with "Speak", and so "warm" and "playful" together
  // describe somebody rather than contradicting each other.
  all(persona, Answers.TONE).mapNotNull(::toneInWords).forEach(said::add)

  first(persona, Answers.LENGTH)?.let {
    when (it) {
      "short" -> "Answer in a sentence or two unless more is genuinely needed."
      "medium" -> "Answer in a short paragraph. Expand only when asked."
      "full" -> "Answer as fully as the question deserves."
      else -> null
    }?.let(said::add)
  }

  first(persona, Answers.LANGUAGE)?.let {
    said.add("Answer in $it, whatever language the question was asked in.")
  }

  first(persona, Answers.PLACE)?.let {
    said.add("The user is in $it. Take that as the place for times, dates and anything local.")
  }

  first(persona, Answers.UNITS)?.let {
    when (it) {
      "metric" -> "Use metric units and Celsius."
      "imperial" -> "Use imperial units and Fahrenheit."
      else -> null
    }?.let(said::add)
  }

  val uses = all(persona, Answers.USES).mapNotNull(::useInWords)
  if (uses.isNotEmpty()) {
    said.add("The user mostly asks about ${inAList(uses)}. Expect those, but never assume one.")
  }

  val askFirst = all(persona, Answers.ASK_FIRST).mapNotNull(::askFirstInWords)
  if (askFirst.isNotEmpty()) {
    // Deliberately absolute. This is the one part of the persona that constrains an action
    // rather than a manner, so it is written so a small model cannot read it as advice.
    said.add(
      "Always ask the user before you ${inAList(askFirst)}, even if a setting would allow it."
    )
  }

  first(persona, Answers.UNSURE)?.let {
    when (it) {
      "say_so" -> "When you do not know something, say so plainly. Never fill a gap with a guess."
      "look_it_up" ->
        "When you do not know something, look it up before answering. If you cannot, say so " +
          "plainly rather than guessing."
      else -> null
    }?.let(said::add)
  }

  first(persona, Answers.BIG)?.let {
    when (it) {
      // "If you can" rather than "always", because the tool that does this is withheld when no
      // server is configured, and an instruction to use something absent is how a turn gets
      // spent apologising.
      "delegate" ->
        "When something needs a much larger model - real research, long writing, heavy " +
          "analysis - hand it to the server model if you can rather than attempting it here."
      "here" ->
        "Answer here yourself. Do not hand work to a server model unless the user asks you to."
      else -> null
    }?.let(said::add)
  }

  first(persona, Answers.ABOUT)?.let { said.add("The user says: $it") }

  return said.joinToString(" ")
}

private fun toneInWords(id: String): String? =
  when (id) {
    "plain" -> "Get to the point. No preamble, no sign-off."
    "warm" -> "Be warm and direct, as a friend would be, without being effusive."
    "precise" -> "Be precise: prefer exact words to approximate ones, and say what you are unsure of."
    "playful" -> "Keep it light, with some dry humour, but never at the cost of being clear."
    else -> null
  }

private fun useInWords(id: String): String? =
  when (id) {
    "messages" -> "messages and calls"
    "calendar" -> "their calendar and reminders"
    "music" -> "music and what is playing"
    "lookups" -> "looking things up"
    "memory" -> "things they asked you to remember"
    "phone" -> "the phone's own settings"
    "writing" -> "writing and drafting"
    else -> null
  }

private fun askFirstInWords(id: String): String? =
  when (id) {
    "send" -> "send a message"
    "call" -> "place a call"
    "settings" -> "change a setting on the phone"
    "play" -> "start playing something"
    "open" -> "open an app"
    else -> null
  }

/** "a, b and c" - because a model reading "a, b, c" sometimes treats the last as an aside. */
internal fun inAList(items: List<String>): String =
  when (items.size) {
    0 -> ""
    1 -> items.first()
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
  }

private fun first(persona: Persona, question: Answers): String? =
  persona[question.id]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun all(persona: Persona, question: Answers): List<String> =
  persona[question.id].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The answers as stored, which is one JSON object in the settings.
 *
 * Unreadable reads as unanswered rather than throwing: this is on the path of building the
 * prompt for every turn, and a value damaged by a half-finished write must not stop Lin
 * answering - it should only make it a little less personal until the quiz is taken again.
 */
fun personaFrom(stored: String?): Persona {
  val text = stored.orEmpty().trim()
  if (text.isEmpty()) return emptyMap()
  val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
  val out = mutableMapOf<String, List<String>>()
  for (key in json.keys()) {
    if (Answers.byId(key) == null) continue
    when (val value = json.opt(key)) {
      is String -> if (value.isNotBlank()) out[key] = listOf(value)
      is JSONArray ->
        (0 until value.length())
          .mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
          .takeIf { it.isNotEmpty() }
          ?.let { out[key] = it }
      else -> Unit
    }
  }
  return out
}

fun storedPersona(persona: Persona): String {
  val json = JSONObject()
  persona.forEach { (key, values) ->
    val kept = values.map { it.trim() }.filter { it.isNotEmpty() }
    if (kept.isEmpty() || Answers.byId(key) == null) return@forEach
    if (Answers.byId(key)?.kind == Answers.Kind.TEXT) json.put(key, kept.first())
    else json.put(key, JSONArray(kept))
  }
  return json.toString()
}

/** How many questions have an answer, for a screen that says how far through somebody is. */
fun answeredCount(persona: Persona): Int = Answers.entries.count { persona[it.id]?.any { a -> a.isNotBlank() } == true }

/**
 * The answers Lin ships with.
 *
 * Not a guess at an average person. These are the answers the first person to use Lin actually
 * gave, question by question, and they are here because the alternative was worse: a new
 * install with an untouched quiz got no personal half at all, which made Lin a stranger that
 * had to be told the same four things in every conversation.
 *
 * Two questions are deliberately missing rather than filled in. Language is absent because
 * "whichever I asked in" is the absence of an instruction, not an instruction to answer in
 * English - [personaPrompt] says nothing at all and the model follows the question. ASK_FIRST
 * is absent because the answer was "trust the switches": that question overrides the Tools tab,
 * so a default of "always ask before sending" would quietly make those switches a lie.
 *
 * Anyone the answers do not fit replaces them by answering a single question in the quiz - see
 * [personaOrDefault], which is all-or-nothing on purpose. Half these answers mixed with half of
 * somebody else's would be a prompt nobody wrote.
 */
val DEFAULT_PERSONA: Persona =
  mapOf(
    Answers.NAME.id to listOf("Sam"),
    Answers.TONE.id to listOf("warm", "playful"),
    Answers.LENGTH.id to listOf("medium"),
    Answers.PLACE.id to listOf("Lisbon, Israel"),
    Answers.UNITS.id to listOf("metric"),
    Answers.USES.id to listOf("messages", "calendar", "music", "lookups"),
    Answers.UNSURE.id to listOf("look_it_up"),
    Answers.BIG.id to listOf("delegate"),
    Answers.ABOUT.id to listOf("Mostly reminders and brainstorming."),
  )

/**
 * The answers to build the prompt from: somebody's own, or the ones Lin ships with.
 *
 * All or nothing, and the one answered question is the switch. Merging the two - taking a name
 * from one and a city from the other - would give somebody who answered "call me Sam" a prompt
 * that also says they are in Lisbon, which is worse than either prompt on its own and
 * impossible to see from the quiz screen.
 */
fun personaOrDefault(persona: Persona): Persona = persona.ifEmpty { DEFAULT_PERSONA }

/** Whether the shipped answers are the ones in use, for a screen that should say so. */
fun usingDefaultPersona(persona: Persona): Boolean = persona.isEmpty()
