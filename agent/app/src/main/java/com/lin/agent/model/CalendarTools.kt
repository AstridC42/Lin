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

package com.lin.agent.model

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.lin.agent.core.calendar.CalendarRead
import com.lin.agent.core.calendar.Calendars
import com.lin.agent.core.calendar.DEFAULT_DAYS_AHEAD
import com.lin.agent.core.calendar.Timed
import com.lin.agent.core.calendar.clockTime
import com.lin.agent.core.calendar.dayFromWords
import com.lin.agent.core.calendar.describeEvents
import com.lin.agent.core.calendar.endOfDaysAhead
import com.lin.agent.core.calendar.mayActWithoutConfirming
import com.lin.agent.core.calendar.startOfEvent
import com.lin.agent.core.calendar.startOfToday
import com.lin.agent.core.calendar.timerMinutes
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult

/** Reads what is coming up. The only one of these that touches the calendar provider at all. */
class CalendarTool(private val calendars: Calendars) : AgentTool {

  override val name = "whats_on"

  override val description =
    "Look at the user's calendar for what is coming up, today or over the next few days."

  override val parameters =
    listOf(
      ToolParameter(
        name = "days",
        type = ParamType.INTEGER,
        description = "How many days ahead to look. 1 means the rest of today.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["days"] as? Number)?.toInt() ?: DEFAULT_DAYS_AHEAD
    val days = asked.coerceIn(1, MAX_DAYS)

    val now = System.currentTimeMillis()
    // From the start of today rather than from this minute, so "what is on today" still
    // includes the ten o'clock meeting when it is asked at eleven. Midnight where the person
    // is, not UTC midnight - see startOfToday.
    return when (val read = calendars.between(startOfToday(now), endOfDaysAhead(now, days))) {
      is CalendarRead.Ok -> ToolResult.Ok(describeEvents(read.events, now))
      CalendarRead.NotPermitted ->
        ToolResult.Failed(
          "Lin cannot see the calendar yet - the user grants that in settings. Tell them, " +
            "rather than guessing at what might be on."
        )
      is CalendarRead.Failed -> ToolResult.Failed(read.why)
    }
  }

  private companion object {
    const val MAX_DAYS = 31
  }
}

/**
 * Puts an event in front of the calendar app, filled in.
 *
 * `ACTION_INSERT` rather than a write, which is the staged-action rule again and in the place
 * it is cheapest to honour: the calendar app's own editor opens with the fields populated and
 * the user saves. This app holds no calendar write permission at all, so a model that
 * misreads a date cannot put anything in somebody's week - the worst it can do is open an
 * editor showing the wrong day, which is visible before it is saved.
 */
class AddToCalendarTool(
  private val context: Context,
  private val guard: LaunchGuard,
) : AgentTool {

  override val name = "add_to_calendar"

  override val description =
    "Open the calendar with a new event filled in. The user saves it - it is not added on its " +
      "own, so say it is ready rather than saying it is in the calendar."

  override val parameters =
    listOf(
      ToolParameter(name = "title", type = ParamType.STRING, description = "What the event is."),
      ToolParameter(
        name = "when",
        type = ParamType.STRING,
        description =
          "Preferred. One of: today, tomorrow, a weekday name, or 'in 3 days'. Use this " +
            "rather than working the date out yourself.",
      ),
      ToolParameter(name = "year", type = ParamType.INTEGER, description = "Four digits."),
      ToolParameter(name = "month", type = ParamType.INTEGER, description = "1 to 12."),
      ToolParameter(name = "day", type = ParamType.INTEGER, description = "Day of the month."),
      ToolParameter(name = "hour", type = ParamType.INTEGER, description = "0 to 23. Omit for all day."),
      ToolParameter(name = "minute", type = ParamType.INTEGER, description = "0 to 59."),
      ToolParameter(
        name = "minutes_long",
        type = ParamType.INTEGER,
        description = "How long it lasts. Defaults to an hour.",
      ),
      ToolParameter(name = "location", type = ParamType.STRING, description = "Where, if said."),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val title = (arguments["title"] as? String)?.trim().orEmpty()
    if (title.isEmpty()) return ToolResult.Failed("The event needs a name. Ask what it is for.")

    // A named day is worked out from the clock here, rather than trusting the model's
    // arithmetic - which was observed landing a fortnight out while being told today's date.
    // Numbers are still accepted, for anything the words cannot name.
    val named = (arguments["when"] as? String)?.let { dayFromWords(it, System.currentTimeMillis()) }
    val start =
      startOfEvent(
        year = named?.year ?: (arguments["year"] as? Number)?.toInt(),
        month = named?.month ?: (arguments["month"] as? Number)?.toInt(),
        day = named?.day ?: (arguments["day"] as? Number)?.toInt(),
        hour = (arguments["hour"] as? Number)?.toInt(),
        minute = (arguments["minute"] as? Number)?.toInt(),
      )
        ?: return ToolResult.Failed(
          "That is not a date this can use. Say when as 'today', 'tomorrow', a weekday " +
            "name or 'in 3 days' - do not work the date out yourself."
        )

    if (!guard.allow(name, System.currentTimeMillis())) {
      return ToolResult.Failed(
        "The calendar was just opened with this. It is on screen waiting for the user - do not " +
          "open it again. Tell them it is ready and stop."
      )
    }

    val minutes = ((arguments["minutes_long"] as? Number)?.toInt() ?: DEFAULT_MINUTES).coerceIn(0, MAX_MINUTES)

    val intent =
      Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + minutes * 60_000L)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    (arguments["location"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let {
      intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
    }

    return try {
      context.startActivity(intent)
      // "Done" first, deliberately. The honest part - that it is unsaved - has to be said,
      // and a small model reads a result beginning with "not saved" as a failure and calls the
      // tool again. That loop is what the guard above catches; this is what avoids provoking it.
      ToolResult.Ok(
        "Done: the calendar is open with '$title' filled in, waiting for the user to save it. " +
          "Nothing more to do. Tell them it is ready - not that it is in their calendar."
      )
    } catch (e: ActivityNotFoundException) {
      ToolResult.Failed("There is no calendar app on this phone to open.")
    }
  }

  private companion object {
    const val DEFAULT_MINUTES = 60
    const val MAX_MINUTES = 24 * 60
  }
}

/**
 * Timers and alarms, which are not the same kind of thing.
 *
 * A timer is set outright; an alarm opens the clock app with the time filled in. The
 * difference is what the mistake costs and it is decided by
 * [com.lin.agent.core.calendar.mayActWithoutConfirming] rather than here: a timer runs for
 * minutes, is visible the moment it is set and is cancelled with a tap, while an alarm fires
 * hours later - often in the night - and a misheard "seven" for "eleven" is found out by being
 * woken by it.
 */
class AlarmTool(private val context: Context, private val guard: LaunchGuard) : AgentTool {

  override val name = "set_timer_or_alarm"

  override val description =
    "Set a timer for a number of minutes, or an alarm for a time of day. A timer is set " +
      "straight away; an alarm opens the clock for the user to confirm."

  override val parameters =
    listOf(
      ToolParameter(
        name = "minutes",
        type = ParamType.INTEGER,
        description = "For a timer: how many minutes from now. Leave out for an alarm.",
      ),
      ToolParameter(
        name = "hour",
        type = ParamType.INTEGER,
        description = "For an alarm: the hour, 0 to 23. Leave out for a timer.",
      ),
      ToolParameter(name = "minute", type = ParamType.INTEGER, description = "For an alarm: 0 to 59."),
      ToolParameter(name = "label", type = ParamType.STRING, description = "What it is for."),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val label = (arguments["label"] as? String)?.trim().orEmpty()
    val minutes = timerMinutes((arguments["minutes"] as? Number)?.toInt())

    if (!guard.allow(name, System.currentTimeMillis())) {
      return ToolResult.Failed(
        "That was just done. Do not do it again - tell the user what is already set."
      )
    }

    if (minutes != null) {
      val intent =
        Intent(AlarmClock.ACTION_SET_TIMER)
          .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
          .putExtra(AlarmClock.EXTRA_SKIP_UI, mayActWithoutConfirming(Timed.TIMER))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
      return start(intent, "A timer is running for $minutes minutes.")
    }

    val time =
      clockTime((arguments["hour"] as? Number)?.toInt(), (arguments["minute"] as? Number)?.toInt())
        ?: return ToolResult.Failed(
          "That is neither a number of minutes nor a time of day. For a timer give minutes; " +
            "for an alarm give the hour as 0 to 23."
        )

    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, mayActWithoutConfirming(Timed.ALARM))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)

    return start(
      intent,
      "Done: the clock is open with an alarm for %02d:%02d, waiting for the user to confirm it. "
        .format(time.hour, time.minute) +
        "Nothing more to do. Say it is ready rather than saying it is set.",
    )
  }

  private fun start(intent: Intent, said: String): ToolResult =
    try {
      context.startActivity(intent)
      ToolResult.Ok(said)
    } catch (e: ActivityNotFoundException) {
      ToolResult.Failed("There is no clock app on this phone that can do that.")
    }
}
