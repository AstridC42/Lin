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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.standing.StandingTask
import com.lin.agent.core.standing.Trigger
import com.lin.agent.core.standing.daysFrom
import com.lin.agent.core.standing.describeRoutine
import com.lin.agent.core.standing.storedTasks
import com.lin.agent.core.standing.tasksFrom
import com.lin.agent.core.standing.timeFrom
import com.lin.agent.core.standing.triggerFrom
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import kotlinx.coroutines.flow.first

/** Creates a routine from what the user said. Not staged: it is listed and undone by asking. */
class AddRoutineTool(private val context: Context, private val settings: SettingsRepository) : AgentTool {

  override val name = "add_routine"

  override val description =
    "Create a routine: something Lin does by itself at a time, when the phone starts charging, " +
      "or when a Bluetooth device (car, earbuds, watch) connects or disconnects."

  override val parameters =
    listOf(
      ToolParameter("do", ParamType.STRING,
        "What Lin should do, in plain words, e.g. 'silent mode and do not disturb on' or 'tell me the weather'."),
      ToolParameter("when", ParamType.STRING,
        "time, charging, bluetooth connected or bluetooth disconnected."),
      ToolParameter("time", ParamType.STRING, "For a time routine: like 07:30 or 7pm.", required = false),
      ToolParameter("days", ParamType.STRING, "weekdays, weekends, every day, or day names. Every day if not said.", required = false),
      ToolParameter("device", ParamType.STRING, "For Bluetooth: part of the device's name, like car or Buds. Any device if not said.", required = false),
      ToolParameter("speak", ParamType.STRING, "yes to read the result aloud.", required = false),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
    "Use add_routine when the user wants something to happen regularly or automatically " +
      "('every morning at 7...', 'when I connect to the car...', 'when I plug in my phone...'). " +
      "Plain phone commands in a routine run instantly; anything else runs as a full answer."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val ask = (arguments["do"] as? String)?.trim().orEmpty()
    if (ask.isEmpty()) return ToolResult.Failed("Say what the routine should do.")
    val time = timeFrom(arguments["time"] as? String)
    val trigger =
      triggerFrom(arguments["when"] as? String)
        ?: if (time != null) Trigger.TIME
        else return ToolResult.Failed("Say when: a time, when charging, or when a Bluetooth device connects or disconnects.")
    if (trigger == Trigger.TIME && time == null) return ToolResult.Failed("Say what time it should run.")
    val device = (arguments["device"] as? String)?.trim().orEmpty()
    val speak = (arguments["speak"] as? String)?.trim()?.lowercase() in setOf("yes", "true", "aloud", "on")

    val task =
      StandingTask(
        id = java.util.UUID.randomUUID().toString(),
        ask = ask,
        hour = time?.first ?: 8,
        minute = time?.second ?: 0,
        days = daysFrom(arguments["days"] as? String),
        trigger = trigger,
        device = if (trigger == Trigger.TIME || trigger == Trigger.CHARGING) "" else device,
        speak = speak,
      )
    val next = tasksFrom(settings.standingTasks.first()) + task
    settings.setStandingTasks(storedTasks(next))
    Routines.sync(context, next)

    val bluetooth = trigger == Trigger.BLUETOOTH_CONNECTED || trigger == Trigger.BLUETOOTH_DISCONNECTED
    val needsPermission =
      bluetooth && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    val note =
      if (needsPermission)
        " It needs the Nearby devices permission to see Bluetooth connections: tell the user to allow it in Lin's Settings, under Routines."
      else ""
    return ToolResult.Ok("Done - routine added: ${describeRoutine(task)}.$note")
  }
}

class ListRoutinesTool(private val settings: SettingsRepository) : AgentTool {
  override val name = "list_routines"
  override val description = "List the user's routines: what each does and when."
  override val parameters = emptyList<ToolParameter>()
  override val roles = setOf(AgentRole.ORCHESTRATOR)
  override val guidance = "Use list_routines before removing one, or when asked what routines there are."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val tasks = tasksFrom(settings.standingTasks.first())
    if (tasks.isEmpty()) return ToolResult.Ok("There are no routines.")
    return ToolResult.Ok(tasks.withIndex().joinToString("\n") { (i, t) -> "${i + 1}. ${describeRoutine(t)}" })
  }
}

class RemoveRoutineTool(private val context: Context, private val settings: SettingsRepository) : AgentTool {
  override val name = "remove_routine"
  override val description = "Delete one of the user's routines, by its number from list_routines or words from it."
  override val parameters =
    listOf(ToolParameter("which", ParamType.STRING, "The routine's number from list_routines, or words from what it does."))
  override val roles = setOf(AgentRole.ORCHESTRATOR)
  override val guidance = "Use remove_routine to delete a routine; check list_routines first if unsure which."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val which = (arguments["which"] as? String)?.trim().orEmpty()
    val tasks = tasksFrom(settings.standingTasks.first())
    val number = which.toIntOrNull()
    val matches =
      if (number != null) listOfNotNull(tasks.getOrNull(number - 1))
      else tasks.filter { it.ask.contains(which, ignoreCase = true) || describeRoutine(it).contains(which, ignoreCase = true) }
    return when {
      which.isEmpty() || matches.isEmpty() -> ToolResult.Failed("No routine matches '$which'. Use list_routines to see them.")
      matches.size > 1 -> ToolResult.Failed("More than one routine matches '$which'. Ask which, using list_routines.")
      else -> {
        val next = tasks - matches.single()
        settings.setStandingTasks(storedTasks(next))
        Routines.sync(context, next)
        ToolResult.Ok("Done - removed ${describeRoutine(matches.single())}.")
      }
    }
  }
}
