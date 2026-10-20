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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lin.agent.R
import com.lin.agent.core.persona.personaFrom
import com.lin.agent.core.persona.personaOrDefault
import com.lin.agent.core.persona.personaPrompt
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.standing.StandingTask
import com.lin.agent.core.standing.quickSteps
import com.lin.agent.core.tools.disabledFrom
import com.lin.agent.core.tools.quickReply
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.LoopEvent
import com.lin.agent.engine.Message
import com.lin.agent.engine.TurnLoop
import com.lin.agent.engine.ToolResult
import com.lin.agent.engine.capabilityPrompt
import com.lin.agent.voice.Speaker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one routine and delivers what it said: a notification, and out loud if asked.
 *
 * A routine that is nothing but plain commands ("silent mode and do not disturb on") runs those
 * tools directly - instantly, and with no model needed at all, so it works with the server off.
 * Anything else is one full turn with the real tools, as though the user had typed it.
 */
class RoutineRunner(private val context: Context) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun backends(): ChatBackends

    fun tools(): Tools

    fun settings(): SettingsRepository

    fun speaker(): Speaker
  }

  private val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)

  /** The answer, or null when it could not run (no model reachable, nothing said). */
  suspend fun run(task: StandingTask): String? {
    val answer = quickly(task) ?: withModel(task) ?: return null
    notify(task, answer)
    if (task.speak) speak(answer)
    return answer
  }

  private suspend fun quickly(task: StandingTask): String? {
    val steps = quickSteps(task.ask) ?: return null
    val off = disabledFrom(deps.settings().disabledTools.first())
    val registry = deps.tools().registry.value
    val said =
      steps.map { step ->
        val tool = registry.find(AgentRole.ORCHESTRATOR, step.tool)?.takeIf { it.name !in off }
        val result = tool?.let { runCatching { it.run(step.arguments) }.getOrNull() }
        when (result) {
          is ToolResult.Ok -> quickReply(result.text)
          is ToolResult.Failed -> "Could not: " + quickReply(result.message)
          else -> "Could not run ${step.tool}."
        }
      }
    return said.joinToString(" ")
  }

  private suspend fun withModel(task: StandingTask): String? {
    val backend = (deps.backends().readiness() as? ChatReadiness.Ready)?.backend ?: return null
    val off = disabledFrom(deps.settings().disabledTools.first())
    val registry = deps.tools().registry.value
    // No delegation: nobody is waiting, and the chosen backend is already the best reachable.
    val withheld = off + DelegateTool.NAME
    val offered = registry.forRole(AgentRole.ORCHESTRATOR).filterNot { it.name in withheld }
    val who = personaPrompt(personaOrDefault(personaFrom(deps.settings().persona.first())))
    val prompt = buildString {
      append(com.lin.agent.core.calendar.todayLine(System.currentTimeMillis()))
      append(" You are Lin, an assistant running on this phone. ")
      append("This was asked by one of the user's routines rather than typed just now, so do ")
      append("what it says, answer in full and do not ask anything back - nobody is there to reply. ")
      if (task.speak) append("The answer will be read aloud, so keep it short and plain. ")
      if (who.isNotBlank()) append(who).append(' ')
      append(capabilityPrompt(offered))
    }
    val events =
      TurnLoop(backend = backend, registry = registry, role = AgentRole.ORCHESTRATOR, systemPrompt = prompt, withhold = withheld)
        .run(listOf(Message(Message.Role.USER, task.ask)))
        .toList()
    return events.filterIsInstance<LoopEvent.Done>().lastOrNull()?.text?.takeIf { it.isNotBlank() }
  }

  /** Waits for the speech to finish, so the worker is not ended mid-sentence. */
  private suspend fun speak(answer: String) {
    val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
    runCatching { deps.speaker().say(answer, whenDone = { finished.complete(Unit) }) }
      .onFailure { finished.complete(Unit) }
    withTimeoutOrNull(SPEAK_LIMIT_MS) { finished.await() }
  }

  private fun notify(task: StandingTask, answer: String) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "No notification permission, so ${task.id} has nowhere to go")
      return
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, context.getString(R.string.standing_channel), NotificationManager.IMPORTANCE_DEFAULT)
        .apply { description = context.getString(R.string.standing_channel_detail) }
    )
    val open =
      PendingIntent.getActivity(
        context,
        task.id.hashCode(),
        Intent(context, Class.forName("com.lin.agent.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    manager.notify(
      task.id.hashCode(),
      NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(task.ask)
        .setContentText(answer.lineSequence().firstOrNull().orEmpty())
        .setStyle(NotificationCompat.BigTextStyle().bigText(answer))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build(),
    )
  }

  private companion object {
    const val TAG = "LinRoutine"
    const val CHANNEL = "standing"
    const val SPEAK_LIMIT_MS = 90_000L
  }
}
