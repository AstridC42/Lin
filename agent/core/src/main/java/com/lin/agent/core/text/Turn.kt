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

package com.lin.agent.core.text

import java.util.Locale

/** One thing Lin did on a turn, as far as the screen's summary of it is concerned. */
data class Step(val tool: String, val running: Boolean = false, val failed: Boolean = false)

/**
 * What a tool did, in the words a person would use: "Searched the web", or while it runs,
 * "Searching the web".
 *
 * Why not the tool's own name: "search web · Done" is the program describing itself. Every
 * assistant that does this well - Claude's "Searched the web", ChatGPT's "Searched 6 sites",
 * Gemini's "Looking at the web" - says what happened in plain words, and the name is there for
 * whoever taps through to the detail. An unknown tool - one from an MCP server, say - falls back
 * to its name with the underscores taken out, which is still readable and never wrong.
 */
fun stepVerb(tool: String, running: Boolean): String {
  val (doing, done) =
    VERBS[tool]
      ?: tool.substringAfterLast('.').replace('_', ' ').let { plain ->
        val sentence = plain.replaceFirstChar { it.titlecase(Locale.ROOT) }
        sentence to sentence
      }
  return if (running) doing else done
}

/**
 * The whole turn's work in one line: "Searched the web · Read a page ×2 · 1 could not".
 *
 * One line, collapsed, before the answer - where every polished assistant puts it - because
 * the work is how the answer was reached, not the answer. Lin used to give every tool its own
 * full-width card after the answer, and a search that read four pages pushed the reply off
 * the screen beneath a column of plumbing.
 *
 * Repeats of the same tool in a row are counted rather than listed, and failures are counted
 * at the end, so a turn that went wrong says so in the summary itself and not only when
 * somebody opens it. A turn still running says what it is doing now.
 */
fun stepsSummary(steps: List<Step>): String {
  if (steps.isEmpty()) return ""
  steps.lastOrNull { it.running }?.let { return stepVerb(it.tool, running = true) + "…" }
  val runs = mutableListOf<Pair<String, Int>>()
  for (step in steps) {
    val verb = stepVerb(step.tool, running = false)
    val last = runs.lastOrNull()
    if (last != null && last.first == verb) runs[runs.size - 1] = verb to last.second + 1 else runs.add(verb to 1)
  }
  val parts = runs.map { (verb, count) -> if (count > 1) "$verb ×$count" else verb }.toMutableList()
  val failed = steps.count { it.failed }
  if (failed > 0) parts.add("$failed could not")
  return parts.joinToString(" · ")
}

private val VERBS: Map<String, Pair<String, String>> =
  mapOf(
    "search_web" to ("Searching the web" to "Searched the web"),
    "read_page" to ("Reading a page" to "Read a page"),
    "remind_me" to ("Setting a reminder" to "Set a reminder"),
    "list_reminders" to ("Checking reminders" to "Checked reminders"),
    "cancel_reminder" to ("Cancelling a reminder" to "Cancelled a reminder"),
    "whats_on" to ("Checking the calendar" to "Checked the calendar"),
    "add_to_calendar" to ("Adding to the calendar" to "Added to the calendar"),
    "set_timer_or_alarm" to ("Setting the clock" to "Set the clock"),
    "weather" to ("Checking the weather" to "Checked the weather"),
    "media" to ("Checking what's playing" to "Checked what's playing"),
    "play_music" to ("Starting music" to "Started music"),
    "phone_setting" to ("Changing a setting" to "Changed a setting"),
    "phone_action" to ("Using the phone" to "Used the phone"),
    "send_message" to ("Writing a message" to "Wrote a message"),
    "reply_to_message" to ("Writing a reply" to "Wrote a reply"),
    "unread_messages" to ("Checking messages" to "Checked messages"),
    "contact" to ("Looking up a contact" to "Looked up a contact"),
    "navigate" to ("Opening directions" to "Opened directions"),
    "open_app" to ("Opening an app" to "Opened an app"),
    "device_status" to ("Checking the phone" to "Checked the phone"),
    "read_screen" to ("Reading the screen" to "Read the screen"),
    "find_photo" to ("Looking through photos" to "Looked through photos"),
    "remember" to ("Remembering" to "Remembered"),
    "recall" to ("Recalling" to "Recalled"),
    "list_documents" to ("Checking documents" to "Checked documents"),
    "read_document" to ("Reading a document" to "Read a document"),
    "write_document" to ("Writing a document" to "Wrote a document"),
    "delegate" to ("Asking the server model" to "Asked the server model"),
  )

/** An answer with its source list taken off the end, and the addresses that list held. */
data class Sourced(val body: String, val sources: List<String>)

/**
 * The answer's "Sources:" list, lifted out so the screen can show it as chips.
 *
 * Lin is told to end a web answer with that list, and it does - as a block of long addresses
 * that reads like a bibliography at the bottom of a chat message. Every assistant that cites
 * well shows sources as small labelled chips instead. So the list is found, taken off the
 * body, and handed back as addresses. Only a trailing list counts: "sources" in the middle of
 * a sentence is a word, and a list with no addresses in it is left exactly where it was.
 */
fun splitSources(answer: String): Sourced {
  val lines = answer.lines()
  val heading = lines.indexOfLast { SOURCES_HEADING.matches(it.trim()) }
  if (heading < 0) return Sourced(answer, emptyList())
  val after = lines.drop(heading + 1)
  if (after.any { it.isNotBlank() && URL.find(it) == null }) return Sourced(answer, emptyList())
  val urls = after.mapNotNull { URL.find(it)?.value?.trimEnd('.', ',', ')', ']') }.distinct()
  if (urls.isEmpty()) return Sourced(answer, emptyList())
  return Sourced(lines.take(heading).joinToString("\n").trimEnd(), urls)
}

/** "www.nvidia.com/en-us/…" as "nvidia.com": what a chip has room for and a person recognises. */
fun domainOf(url: String): String =
  url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")

private val SOURCES_HEADING = Regex("^[#*\\s]*sources?\\s*:?[*\\s]*:?$", RegexOption.IGNORE_CASE)
private val URL = Regex("https?://[^\\s)\\]>\"]+")

/**
 * What the empty screen says first: "Good evening, Looper."
 *
 * The time of day and the person's name, because both are known and a greeting that uses
 * neither is a greeting from nobody in particular. No name, no comma.
 */
fun greetingFor(hour: Int, name: String): String {
  val part =
    when (hour) {
      in 5..11 -> "Good morning"
      in 12..17 -> "Good afternoon"
      in 18..22 -> "Good evening"
      else -> "Hello"
    }
  val who = name.trim()
  return if (who.isEmpty()) "$part." else "$part, $who."
}
