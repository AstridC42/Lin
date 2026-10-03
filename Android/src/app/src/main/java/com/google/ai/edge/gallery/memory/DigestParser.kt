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

package com.google.ai.edge.gallery.memory

import com.google.ai.edge.gallery.proto.NoteKind

/** What one pass of the digester understood a conversation to be. */
data class ParsedDigest(
  val title: String,
  val summary: String,
  val topics: List<String>,
  val notes: List<CandidateNote>,
)

/**
 * Turning what the model wrote about a conversation into something storable.
 *
 * Nobody sees this happen. It runs after the conversation is over, against a small model, on a
 * phone, with no one to notice that the reply came back wrapped in a code fence or that it
 * answered in prose after being asked for fields. Whatever comes back has to produce either a
 * usable digest or an honest nothing - never a crash in a background worker, and never a note
 * whose text is "SUMMARY:" because a label was mistaken for content.
 *
 * So the format asked for is lines with labels rather than JSON: a 2B model closes brackets
 * wrong far more often than it misspells a word at the start of a line, and a half-parsed line
 * costs one note where a half-parsed object costs the lot.
 */
object DigestParser {

  /** Hard ceiling on a title, which is a handful of words by design. */
  const val MAX_TITLE_CHARS = 70

  /** And on the summary, which is a paragraph, not a retelling. */
  const val MAX_SUMMARY_CHARS = 600

  const val MAX_TOPICS = 6

  /** The most notes one conversation may contribute. Past this it is inventing. */
  const val MAX_NOTES_PER_CONVERSATION = 8

  /**
   * What the digester is asked.
   *
   * Written in the imperative and with the format shown rather than described, because showing
   * is what small models follow. The instruction not to invent is the load-bearing one: a model
   * asked for facts about somebody will produce facts about somebody.
   */
  fun prompt(transcript: String): String =
    """
    You are summarising a finished conversation so it can be remembered later.

    Reply using exactly these labels, one per line, and nothing else:

    TITLE: a few words naming the conversation
    SUMMARY: one short paragraph on what was discussed and what came of it
    TOPICS: up to six comma-separated words
    NOTE [kind]: one durable fact about the user

    Write a NOTE line only for something that will still be true next month and that the user
    said or clearly implied about themselves. Do not write notes about what you did, about the
    weather, or about anything you are not sure of. Most conversations produce no notes at all,
    and none is a perfectly good answer. Never guess.

    kind is one of: identity, preference, relationship, routine, project, context

    The conversation:

    $transcript
    """
      .trimIndent()

  private val LABEL = Regex("^\\s*[-*>\\s]*([A-Za-z]+)\\s*(?:\\[([^]]*)])?\\s*[::]\\s*(.*)$")

  /**
   * Reads [reply] into a digest, or null when there is nothing usable in it.
   *
   * Everything is optional except having found *something*: a reply with a summary and no notes
   * is the common case and is perfectly good. A reply with notes and no summary is still worth
   * keeping - the notes are the part that compounds.
   */
  fun parse(reply: String): ParsedDigest? {
    val body = stripFences(reply)
    var title = ""
    var summary = ""
    val topics = mutableListOf<String>()
    val notes = mutableListOf<CandidateNote>()

    for (rawLine in body.lines()) {
      val match = LABEL.find(rawLine) ?: continue
      val label = match.groupValues[1].lowercase()
      val qualifier = match.groupValues[2]
      val value = match.groupValues[3].trim().trim('*', '"').trim()
      if (value.isEmpty()) continue
      when (label) {
        "title" -> if (title.isEmpty()) title = cap(value, MAX_TITLE_CHARS)
        "summary" -> if (summary.isEmpty()) summary = cap(value, MAX_SUMMARY_CHARS)
        "topics", "topic" ->
          topics +=
            value
              .split(',', ';')
              .map { it.trim().trim('.').lowercase() }
              .filter { it.isNotBlank() && it.length <= 40 }
        "note", "notes", "fact" ->
          if (notes.size < MAX_NOTES_PER_CONVERSATION && !looksLikeRefusal(value)) {
            notes += CandidateNote(text = value, kind = kindOf(qualifier))
          }
      }
    }

    if (title.isEmpty() && summary.isEmpty() && notes.isEmpty()) return null
    return ParsedDigest(
      title = title,
      summary = summary,
      topics = topics.distinct().take(MAX_TOPICS),
      notes = notes,
    )
  }

  /**
   * A model told "none is a perfectly good answer" will sometimes write that out as a note.
   * Stored, it becomes a thing Lin believes about the user.
   */
  private fun looksLikeRefusal(value: String): Boolean {
    val lower = value.lowercase().trim().trimEnd('.')
    return lower in
      setOf(
        "none",
        "n/a",
        "na",
        "nothing",
        "no notes",
        "none found",
        "no durable facts",
        "no new information",
        "unknown",
        "-",
      )
  }

  private fun kindOf(qualifier: String): NoteKind =
    when (qualifier.trim().lowercase()) {
      "identity" -> NoteKind.NOTE_KIND_IDENTITY
      "preference" -> NoteKind.NOTE_KIND_PREFERENCE
      "relationship" -> NoteKind.NOTE_KIND_RELATIONSHIP
      "routine" -> NoteKind.NOTE_KIND_ROUTINE
      "project" -> NoteKind.NOTE_KIND_PROJECT
      else -> NoteKind.NOTE_KIND_CONTEXT
    }

  /** Drops a markdown code fence wrapped around the whole reply, which several models add. */
  private fun stripFences(reply: String): String {
    val trimmed = reply.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
  }

  private fun cap(value: String, max: Int): String {
    val collapsed = value.replace(Regex("\\s+"), " ").trim()
    if (collapsed.length <= max) return collapsed
    return collapsed.take(max).substringBeforeLast(' ').ifBlank { collapsed.take(max) } + "..."
  }
}
