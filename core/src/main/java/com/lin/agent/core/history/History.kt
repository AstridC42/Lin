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

package com.lin.agent.core.history

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One line of a saved conversation: who said it, and what. */
data class SavedLine(val fromUser: Boolean, val text: String)

/**
 * One past conversation, as the history drawer keeps it.
 *
 * Every assistant people already use keeps their conversations - Gemini, ChatGPT and Claude all
 * have the same drawer - and Lin used to throw each one away the moment a new one began. A
 * conversation somebody wants to come back to is not a training example or a memory; it is
 * just the conversation, kept.
 */
data class SavedChat(val id: String, val title: String, val updatedAt: Long, val lines: List<SavedLine>)

/**
 * What a conversation is called in the list: its first question, cut at a word.
 *
 * The first question rather than a summary from the model: it costs nothing, it is never wrong
 * about what was asked, and it is what the person will recognise - they asked it.
 */
fun chatTitle(lines: List<SavedLine>, longest: Int = 48): String {
  val first = lines.firstOrNull { it.fromUser && it.text.isNotBlank() }?.text ?: return "New conversation"
  val flat = first.replace(Regex("\\s+"), " ").trim()
  if (flat.length <= longest) return flat
  val cut = flat.take(longest).substringBeforeLast(' ').ifEmpty { flat.take(longest) }
  return "$cut…"
}

/**
 * The list with [chat] in it, most recent first, and no more than [keep] of them.
 *
 * Replaces an earlier copy of the same conversation rather than adding a second, because a
 * conversation is saved after every answer. Capped so a phone used every day for a year does
 * not carry a history file larger than everything else Lin stores together.
 */
fun withChat(chats: List<SavedChat>, chat: SavedChat, keep: Int = 200): List<SavedChat> =
  (chats.filterNot { it.id == chat.id } + chat).sortedByDescending { it.updatedAt }.take(keep)

/** Conversations whose title or words contain every word searched for, most recent first. */
fun searchChats(chats: List<SavedChat>, query: String): List<SavedChat> {
  val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
  if (words.isEmpty()) return chats
  return chats.filter { chat ->
    val text = (chat.title + " " + chat.lines.joinToString(" ") { it.text }).lowercase(Locale.ROOT)
    words.all { text.contains(it) }
  }
}

/** The headings the drawer groups by - the same ones ChatGPT and Gemini use. */
enum class When {
  TODAY,
  YESTERDAY,
  THIS_WEEK,
  OLDER,
}

/** Which heading a conversation goes under, by the day it was last touched where the person is. */
fun whenHeading(updatedAt: Long, now: Long, zone: ZoneId): When {
  val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
  val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
  val days = today.toEpochDay() - day.toEpochDay()
  return when {
    days <= 0 -> When.TODAY
    days == 1L -> When.YESTERDAY
    days < 7 -> When.THIS_WEEK
    else -> When.OLDER
  }
}

/**
 * The history as stored: one JSON array. Unreadable rows are skipped one at a time, so a single
 * damaged conversation cannot take the rest of somebody's history with it.
 */
fun chatsFrom(stored: String?): List<SavedChat> {
  val array = runCatching { JSONArray(stored.orEmpty().ifBlank { "[]" }) }.getOrNull() ?: return emptyList()
  return (0 until array.length())
    .mapNotNull { index ->
      val row = array.optJSONObject(index) ?: return@mapNotNull null
      val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val lines = row.optJSONArray("lines") ?: JSONArray()
      val read =
        (0 until lines.length()).mapNotNull { at ->
          lines.optJSONObject(at)?.let { SavedLine(it.optBoolean("u"), it.optString("t")) }
        }
      if (read.isEmpty()) return@mapNotNull null
      SavedChat(id, row.optString("title").ifBlank { chatTitle(read) }, row.optLong("at"), read)
    }
    .sortedByDescending { it.updatedAt }
}

fun storedChats(chats: List<SavedChat>): String {
  val array = JSONArray()
  for (chat in chats) {
    val lines = JSONArray()
    chat.lines.forEach { lines.put(JSONObject().put("u", it.fromUser).put("t", it.text)) }
    array.put(JSONObject().put("id", chat.id).put("title", chat.title).put("at", chat.updatedAt).put("lines", lines))
  }
  return array.toString()
}

/**
 * The model's name as a person would say it: "gemma-4-12B · server", not
 * "hf.co/unsloth/gemma-4-12B-it-qat-GGUF:UD-Q4_K_XL · server".
 *
 * The full name was what the header showed, all of it, under the app's name - accurate and
 * unreadable. The path, the GGUF packaging, the quantisation tag and the "-it"/"-qat" training
 * suffixes are detail for the settings screen; the family and size are what tell somebody which
 * model is answering. An Ollama tag like "qwen3:32b" is the size and is kept.
 */
fun shortModelName(id: String): String {
  val where = id.substringAfter(" · ", "").let { if (it.isEmpty()) "" else " · $it" }
  var name = id.substringBefore(" · ").trim().substringAfterLast('/')
  val packaged = name.contains("gguf", ignoreCase = true)
  if (packaged) {
    name = name.substringBefore(':')
    name = name.replace(Regex("-?gguf", RegexOption.IGNORE_CASE), "")
  }
  name = name.replace(Regex("(-(it|qat|instruct|chat))+$", RegexOption.IGNORE_CASE), "")
  return name.ifBlank { id } + where
}
