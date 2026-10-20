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

package com.lin.agent.core.standing

import com.lin.agent.core.tools.QuickCommand
import com.lin.agent.core.tools.quickCommand
import java.util.Locale

/** What starts a routine. */
enum class Trigger {
  TIME,
  CHARGING,
  BLUETOOTH_CONNECTED,
  BLUETOOTH_DISCONNECTED,
}

/** Something that happened on the phone that a routine can start from. */
sealed interface RoutineEvent {
  data object StartedCharging : RoutineEvent

  data class BluetoothConnected(val device: String) : RoutineEvent

  data class BluetoothDisconnected(val device: String) : RoutineEvent
}

/**
 * The routines [event] starts. Days still apply (empty means any day), and a routine that ran
 * from an event in the last [EVENT_GAP_MS] does not run again - a watch or a car that drops and
 * reconnects should not run "arrive in the car" four times.
 */
fun firedBy(event: RoutineEvent, tasks: List<StandingTask>, dayOfWeek: Int, nowMillis: Long): List<StandingTask> =
  tasks.filter { task ->
    task.enabled &&
      task.ask.isNotBlank() &&
      (task.days.isEmpty() || dayOfWeek in task.days) &&
      nowMillis - task.lastRunAt >= EVENT_GAP_MS &&
      when (event) {
        RoutineEvent.StartedCharging -> task.trigger == Trigger.CHARGING
        is RoutineEvent.BluetoothConnected ->
          task.trigger == Trigger.BLUETOOTH_CONNECTED && deviceMatches(task.device, event.device)
        is RoutineEvent.BluetoothDisconnected ->
          task.trigger == Trigger.BLUETOOTH_DISCONNECTED && deviceMatches(task.device, event.device)
      }
  }

/** Blank matches any device; otherwise either name containing the other, ignoring case. */
fun deviceMatches(wanted: String, actual: String): Boolean {
  val w = wanted.trim().lowercase(Locale.ROOT)
  if (w.isEmpty()) return true
  val a = actual.trim().lowercase(Locale.ROOT)
  if (a.isEmpty()) return false
  return a.contains(w) || w.contains(a)
}

/** Ten minutes. */
const val EVENT_GAP_MS = 10 * 60 * 1000L

fun markEventRun(tasks: List<StandingTask>, id: String, nowMillis: Long): List<StandingTask> =
  tasks.map { if (it.id == id) it.copy(lastRunAt = nowMillis) else it }

/**
 * The routine as plain commands Lin can run without a model, or null when any part of it needs
 * one. "Silent mode and do not disturb on" is two commands; "tell me the weather" is not one.
 * A routine that is all commands runs instantly and works with no model reachable at all.
 */
fun quickSteps(ask: String): List<QuickCommand>? {
  val parts =
    ask.split(Regex("\\s*(?:,|;|\\band then\\b|\\bthen\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE))
      .map { it.trim() }
      .filter { it.isNotEmpty() }
  if (parts.isEmpty()) return null
  val steps = parts.map { quickCommand(it) ?: return null }
  return steps
}

/** "charging", "when I connect to the car", "bluetooth disconnected"... to a trigger. */
fun triggerFrom(said: String?): Trigger? {
  val text = said?.lowercase(Locale.ROOT)?.trim().orEmpty()
  return when {
    text.isEmpty() -> null
    Regex("charg|plug").containsMatchIn(text) -> Trigger.CHARGING
    Regex("disconnect|leave|leaving|lose|lost|off").containsMatchIn(text) &&
      Regex("bluetooth|car|headphone|earbud|buds|watch|speaker|device|disconnect").containsMatchIn(text) ->
      Trigger.BLUETOOTH_DISCONNECTED
    Regex("bluetooth|connect|car|headphone|earbud|buds|watch|speaker|device").containsMatchIn(text) ->
      Trigger.BLUETOOTH_CONNECTED
    Regex("time|clock|at |morning|evening|night|\\d").containsMatchIn(text) -> Trigger.TIME
    else -> null
  }
}

/** "07:30", "7:30am", "7pm", "19", "noon" to an hour and minute. */
fun timeFrom(said: String?): Pair<Int, Int>? {
  val text = said?.lowercase(Locale.ROOT)?.replace(".", "")?.trim().orEmpty()
  if (text.isEmpty()) return null
  if ("noon" in text || "midday" in text) return 12 to 0
  if ("midnight" in text) return 0 to 0
  val match = Regex("(\\d{1,2})(?:[:h ](\\d{2}))?\\s*(am|pm)?").find(text) ?: return null
  var hour = match.groupValues[1].toInt()
  val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
  when (match.groupValues[3]) {
    "pm" -> if (hour < 12) hour += 12
    "am" -> if (hour == 12) hour = 0
  }
  if (hour !in 0..23 || minute !in 0..59) return null
  return hour to minute
}

/** "weekdays", "weekends", "every day", "mon, wed and fri" to day numbers (Monday is 1). */
fun daysFrom(said: String?): Set<Int> {
  val text = said?.lowercase(Locale.ROOT)?.trim().orEmpty()
  if (text.isEmpty() || Regex("every ?day|daily|all").containsMatchIn(text)) return emptySet()
  if ("weekday" in text) return setOf(1, 2, 3, 4, 5)
  if ("weekend" in text) return setOf(6, 7)
  val names = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
  return names.withIndex()
    .filter { (_, n) -> Regex("\\b$n").containsMatchIn(text) }
    .map { it.index + 1 }
    .toSet()
}

/** A routine in one line, for a list Lin reads back. */
fun describeRoutine(task: StandingTask): String {
  val days =
    when (task.days) {
      emptySet<Int>() -> ""
      setOf(1, 2, 3, 4, 5) -> " on weekdays"
      setOf(6, 7) -> " at weekends"
      else -> " on " + task.days.sorted().joinToString(", ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
    }
  val device = task.device.ifBlank { "any Bluetooth device" }
  val start =
    when (task.trigger) {
      Trigger.TIME -> "at ${clockTime(task)}"
      Trigger.CHARGING -> "when the phone starts charging"
      Trigger.BLUETOOTH_CONNECTED -> "when $device connects"
      Trigger.BLUETOOTH_DISCONNECTED -> "when $device disconnects"
    }
  val off = if (task.enabled) "" else " (switched off)"
  return "\"${task.ask}\" $start$days$off"
}
