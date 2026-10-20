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

import org.json.JSONArray
import org.json.JSONObject

/**
 * Something Lin does without being asked that morning.
 *
 * The thing an assistant has to be able to do before "agentic" means anything: a question
 * somebody asks every day is a question they should only have to ask once. "What have I got
 * on today", "what did I miss overnight", "what is the weather for the commute" - all of
 * them are a turn Lin can run on its own and put in a notification.
 *
 * [days] uses Java's day numbering (Monday is 1, Sunday is 7) and empty means every day.
 * Empty rather than all seven, so a task created before this app knew about weekends keeps
 * working rather than silently matching nothing.
 */
data class StandingTask(
  val id: String,
  /** What to ask Lin, in the user's own words. */
  val ask: String,
  val hour: Int,
  val minute: Int,
  val days: Set<Int> = emptySet(),
  val enabled: Boolean = true,
  /**
   * The epoch day this last ran, or 0.
   *
   * A day rather than a timestamp, because the question being answered is "has this already
   * run today" and a timestamp would need the same arithmetic done twice.
   */
  val lastRunDay: Long = 0,
  /** What starts it. A time unless said otherwise, which is what every older task was. */
  val trigger: Trigger = Trigger.TIME,
  /**
   * For a Bluetooth trigger, part of the device's name ("car", "Buds"). Blank means any device.
   */
  val device: String = "",
  /** Read the result aloud as well as putting it in a notification. */
  val speak: Boolean = false,
  /** When an event last started it, in epoch millis, so a flapping connection fires once. */
  val lastRunAt: Long = 0,
) {
  val minuteOfDay: Int
    get() = hour * 60 + minute
}

/**
 * Whether [task] should run now.
 *
 * Four conditions, and the fourth is the one worth arguing about.
 *
 *  - It is switched on.
 *  - Today is one of its days.
 *  - It has not already run today.
 *  - The scheduled time has passed, **and not by too much**.
 *
 * That last clause exists because the worker behind this does not fire at an exact minute: a
 * phone asleep, in a doze window, or switched off runs it late, and "late" can be hours. A
 * morning briefing delivered at eleven at night is not a late briefing, it is a wrong one -
 * so a task whose moment has passed by more than [CATCH_UP_MINUTES] is skipped until
 * tomorrow. Running within the window is deliberate though: a briefing at 08:40 because the
 * phone was asleep at 08:00 is still the briefing somebody wanted.
 */
fun isDue(task: StandingTask, dayOfWeek: Int, minuteOfDay: Int, todayEpochDay: Long): Boolean {
  if (task.trigger != Trigger.TIME) return false
  if (!task.enabled) return false
  if (task.ask.isBlank()) return false
  if (task.days.isNotEmpty() && dayOfWeek !in task.days) return false
  if (task.lastRunDay == todayEpochDay) return false
  val late = minuteOfDay - task.minuteOfDay
  return late in 0..CATCH_UP_MINUTES
}

/** Which of [tasks] should run now, in the order they were created. */
fun dueNow(
  tasks: List<StandingTask>,
  dayOfWeek: Int,
  minuteOfDay: Int,
  todayEpochDay: Long,
): List<StandingTask> = tasks.filter { isDue(it, dayOfWeek, minuteOfDay, todayEpochDay) }

/**
 * How late a task may be and still run: three hours.
 *
 * Long enough to cover a phone that was asleep through its moment, short enough that nothing
 * meant for the morning arrives in the evening.
 */
const val CATCH_UP_MINUTES = 180

/** Marks [id] as having run today, leaving the others alone. */
fun markRun(tasks: List<StandingTask>, id: String, todayEpochDay: Long): List<StandingTask> =
  tasks.map { if (it.id == id) it.copy(lastRunDay = todayEpochDay) else it }

/** The time as a person reads it, for a list on a screen. */
fun clockTime(task: StandingTask): String =
  "%02d:%02d".format(task.hour.coerceIn(0, 23), task.minute.coerceIn(0, 59))

/**
 * Which days, in words.
 *
 * Returns an empty string for "every day" rather than the words for it, because the caller
 * has a string resource for that and a hard-coded English one here could not be translated.
 */
fun dayNumbers(task: StandingTask): List<Int> = task.days.sorted()

// ---- storage ----

/**
 * The tasks as stored, which is one JSON array in the settings.
 *
 * Unreadable reads as no tasks rather than throwing. This is read by a background worker
 * that nothing is waiting on, and a damaged value should stop the routines rather than
 * crash the process that was going to run them.
 */
fun tasksFrom(stored: String?): List<StandingTask> {
  val text = stored.orEmpty().trim()
  if (text.isEmpty()) return emptyList()
  val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
  val found = mutableListOf<StandingTask>()
  for (index in 0 until array.length()) {
    val each = array.optJSONObject(index) ?: continue
    val ask = each.optString("ask").trim()
    val id = each.optString("id").trim()
    if (ask.isEmpty() || id.isEmpty()) continue
    val days = each.optJSONArray("days")
    found.add(
      StandingTask(
        id = id,
        ask = ask,
        // Coerced rather than rejected: a task with a nonsense hour should run at a sane
        // time, not disappear from a list the user can see.
        hour = each.optInt("hour", 8).coerceIn(0, 23),
        minute = each.optInt("minute", 0).coerceIn(0, 59),
        days =
          (0 until (days?.length() ?: 0))
            .mapNotNull { days?.optInt(it)?.takeIf { day -> day in 1..7 } }
            .toSet(),
        enabled = each.optBoolean("enabled", true),
        lastRunDay = each.optLong("lastRunDay", 0),
        trigger = Trigger.entries.firstOrNull { it.name == each.optString("trigger") } ?: Trigger.TIME,
        device = each.optString("device").trim(),
        speak = each.optBoolean("speak", false),
        lastRunAt = each.optLong("lastRunAt", 0),
      )
    )
  }
  return found.distinctBy { it.id }
}

fun storedTasks(tasks: List<StandingTask>): String {
  val array = JSONArray()
  tasks.forEach { task ->
    array.put(
      JSONObject()
        .put("id", task.id)
        .put("ask", task.ask)
        .put("hour", task.hour)
        .put("minute", task.minute)
        .put("days", JSONArray(task.days.sorted()))
        .put("enabled", task.enabled)
        .put("lastRunDay", task.lastRunDay)
        .put("trigger", task.trigger.name)
        .put("device", task.device)
        .put("speak", task.speak)
        .put("lastRunAt", task.lastRunAt)
    )
  }
  return array.toString()
}
