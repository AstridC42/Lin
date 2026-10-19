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

package com.lin.agent.core.tools

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The small on-phone model that turns a short command into an action, before anything bigger
 * is asked: Google's FunctionGemma 270M "Mobile Actions" fine-tune.
 *
 * It knows exactly the seven functions it was trained on, so those are what it is given, word
 * for word as in Google's dataset - a model this size does best on precisely what it saw. What
 * it picks is then translated into Lin's own tools. Four of the seven have a Lin equivalent;
 * the other three (Wi-Fi settings, email, new contact) are left to the main model.
 */
data class ActionSpec(val name: String, val description: String, val parametersSchema: String)

val MOBILE_ACTIONS: List<ActionSpec> =
  listOf(
    ActionSpec("turn_on_flashlight", "Turns the flashlight on.", NO_PARAMETERS),
    ActionSpec("turn_off_flashlight", "Turns the flashlight off.", NO_PARAMETERS),
    ActionSpec(
      "create_calendar_event",
      "Creates a new calendar event.",
      """{"type":"object","properties":{"title":{"type":"string","description":"The title of the event."},""" +
        """"datetime":{"type":"string","description":"The date and time of the event in the format YYYY-MM-DDTHH:MM:SS."}},""" +
        """"required":["title","datetime"]}""",
    ),
    ActionSpec(
      "show_map",
      "Shows a location on the map.",
      """{"type":"object","properties":{"query":{"type":"string","description":"The location to search for. May be the name of a place, a business, or an address."}},"required":["query"]}""",
    ),
  )

private const val NO_PARAMETERS = """{"type":"object","properties":{}}"""

/** The instruction it was trained with, date and all. */
fun mobileActionsPrompt(now: LocalDateTime): String =
  "Current date and time given in YYYY-MM-DDTHH:MM:SS format: " +
    now.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) +
    "\nDay of week is " + now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) +
    "\nYou are a model that can do function calling with the following functions\n"

/**
 * Which of its actions a request could be about, from the words in it.
 *
 * The small model is asked only when one could apply, and its pick is kept only if it is one of
 * them. A 270M model handed "tell me about Paris" can answer show_map(Paris); asking it only
 * when the request mentions a map, directions or a place to go keeps chat out of its hands.
 */
fun actionsFor(said: String): Set<String> {
  val text = said.lowercase(Locale.ROOT)
  if (text.length > 160 || text.split(Regex("\\s+")).size > 25) return emptySet()
  return buildSet {
    if (FLASH.containsMatchIn(text)) addAll(listOf("turn_on_flashlight", "turn_off_flashlight"))
    if (CALENDAR.containsMatchIn(text)) add("create_calendar_event")
    if (MAP.containsMatchIn(text)) add("show_map")
  }
}

/**
 * Lin's tool for what the small model picked, or null when there is none or it does not fit.
 *
 * [allowed] is [actionsFor] the request: a pick outside it is the model guessing.
 */
fun fromMobileAction(name: String, arguments: Map<String, Any?>, allowed: Set<String>): QuickCommand? {
  if (name !in allowed) return null
  return when (name) {
    "turn_on_flashlight" -> QuickCommand("phone_setting", mapOf("setting" to "flashlight", "state" to "on"))
    "turn_off_flashlight" -> QuickCommand("phone_setting", mapOf("setting" to "flashlight", "state" to "off"))
    "show_map" -> {
      val place = (arguments["query"] as? String)?.trim().orEmpty()
      if (place.isEmpty()) null else QuickCommand("navigate", mapOf("destination" to place))
    }
    "create_calendar_event" -> {
      val title = (arguments["title"] as? String)?.trim().orEmpty()
      val at = (arguments["datetime"] as? String)?.trim().orEmpty()
      val time = runCatching { LocalDateTime.parse(at.take(19)) }.getOrNull()
      if (title.isEmpty() || time == null) null
      else
        QuickCommand(
          "add_to_calendar",
          mapOf(
            "title" to title,
            "year" to time.year,
            "month" to time.monthValue,
            "day" to time.dayOfMonth,
            "hour" to time.hour,
            "minute" to time.minute,
          ),
        )
    }
    else -> null
  }
}

private val FLASH = Regex("\\b(flashlight|torch|flash ?light|flash)\\b")
private val CALENDAR = Regex("\\b(calendar|event|meeting|appointment|schedule|book)\\b")
private val MAP = Regex("\\b(map|maps|directions|navigate|navigation|route|take me|show me where|where is)\\b")
