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

import java.util.Locale

/** A tool to run straight away, and what to call it with. */
data class QuickCommand(val tool: String, val arguments: Map<String, String>)

/**
 * The plain commands that need no model at all: "flashlight on", "turn off wi-fi", "open camera".
 *
 * Asking a model to choose a tool for these costs a round trip to choose it and another to say
 * it was done - on a server model that is the second or two of lag before the light comes on,
 * every time. Nothing about "turn on the flashlight" needs judgement, so it is matched here
 * and run at once; everything else goes to the model as before.
 *
 * Deliberately narrow. Anything with a second clause ("and", "then", a comma), a question, or
 * more words than a command needs is left to the model, because a fast wrong action is worse
 * than a slow right one.
 */
fun quickCommand(said: String): QuickCommand? {
  var text = said.lowercase(Locale.ROOT).trim().trimEnd('.', '!').trim()
  if (text.isEmpty() || text.length > 60) return null
  if ('?' in text || ',' in text || ';' in text) return null
  text = text.replace(Regex("\\s+"), " ")
  for (filler in LEADING) text = text.removePrefix(filler).trim()
  for (filler in TRAILING) text = text.removeSuffix(filler).trim()
  val words = text.split(' ')
  if (words.any { it in CLAUSES }) return null

  switchCommand(text)?.let { return it }

  val open = OPEN_WORDS.firstOrNull { text.startsWith("$it ") } ?: return null
  val app = text.removePrefix("$open ").removePrefix("the ").removePrefix("my ").trim()
  // "open the door", "start a timer for ten minutes": an app name is short, and the long
  // ones are requests for something else.
  if (app.isEmpty() || app.split(' ').size > 3) return null
  if (app in NOT_APPS || app.split(' ').any { it in NOT_APPS }) return null
  return QuickCommand("open_app", mapOf("app" to app.removeSuffix(" app").trim()))
}

private fun switchCommand(text: String): QuickCommand? {
  val setting = SWITCHES.entries.firstOrNull { (_, names) -> names.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) } }?.key
    ?: return null
  val words = text.split(' ').toSet()
  val on = words.contains("on") || text.startsWith("enable ") || text.startsWith("activate ")
  val off = words.contains("off") || text.startsWith("disable ") || text.startsWith("deactivate ")
  if (on == off) return null
  // Only the switch and the words for switching it - "turn on the flashlight in five minutes"
  // is a reminder, not a switch.
  val names = SWITCHES.getValue(setting)
  var rest = text
  names.sortedByDescending { it.length }.forEach { rest = rest.replace(it, " ") }
  val leftover = rest.split(' ').filter { it.isNotBlank() && it !in SWITCH_WORDS }
  if (leftover.isNotEmpty()) return null
  return QuickCommand("phone_setting", mapOf("setting" to setting, "state" to if (on) "on" else "off"))
}

private val LEADING = listOf("hey lin", "ok lin", "lin", "please", "can you", "could you", "would you")
private val TRAILING = listOf("please", "for me", "now")
private val CLAUSES = setOf("and", "then", "also", "after", "before", "if", "when", "in", "at", "until")
private val OPEN_WORDS = listOf("open", "launch", "start", "run", "go to")
private val NOT_APPS =
  setOf("door", "window", "timer", "alarm", "a", "an", "it", "that", "this", "up", "conversation", "chat", "new")

private val SWITCHES =
  linkedMapOf(
    "flashlight" to listOf("flashlight", "torch", "flash light", "flash"),
    "wi-fi" to listOf("wi-fi", "wifi", "wi fi"),
    "bluetooth" to listOf("bluetooth"),
    "nfc" to listOf("nfc"),
    "do not disturb" to listOf("do not disturb", "dnd"),
    "airplane mode" to listOf("airplane mode", "aeroplane mode", "flight mode", "airplane"),
  )

private val SWITCH_WORDS =
  setOf("turn", "switch", "put", "on", "off", "the", "my", "enable", "disable", "activate", "deactivate", "mode")

/**
 * What to say after a quick command, from what the tool reported.
 *
 * A tool's report is written for a model - "Done - wi-fi is on. Lin set it directly." - so the
 * first sentence is the part for a person, and the rest is advice to a model that is not there.
 */
fun quickReply(report: String): String {
  val first = report.trim().removePrefix("Done - ").removePrefix("Done. ").trim()
  val sentence = Regex("^.*?[.!](?=\\s|$)").find(first)?.value ?: first
  return sentence.replaceFirstChar { it.uppercase() }
}
