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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lin.agent.R
import com.lin.agent.core.persona.personaFrom
import com.lin.agent.core.persona.personaPrompt
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.standing.StandingTask
import com.lin.agent.core.standing.dueNow
import com.lin.agent.core.standing.markRun
import com.lin.agent.core.standing.storedTasks
import com.lin.agent.core.standing.tasksFrom
import com.lin.agent.core.tools.disabledFrom
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.LoopEvent
import com.lin.agent.engine.Message
import com.lin.agent.engine.TurnLoop
import com.lin.agent.engine.capabilityPrompt
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * The things Lin does without being asked that morning.
 *
 * A question somebody asks every day is a question they should only have to ask once. This
 * runs those: a real turn, with the real tools, and the answer arrives as a notification.
 *
 * **Periodic and modest.** It wakes every quarter of an hour and usually finds nothing to do,
 * which is cheaper than it sounds and far simpler than scheduling an exact alarm per task -
 * exact alarms need a permission that Android grants grudgingly, and a routine is not worth
 * asking somebody for that. Lateness is handled by the rule rather than by the schedule: see
 * [com.lin.agent.core.standing.isDue], which runs a task that is late by up to three hours
 * and skips one that is later than that, because a morning briefing delivered at night is
 * not a late briefing but a wrong one.
 *
 * **Nothing is waiting on it.** A failure is logged and dropped. The one thing it must not do
 * is mark a task as run when it did not answer, which would lose that day's briefing
 * silently - so the mark happens only after a reply.
 */
class StandingWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun backends(): ChatBackends

    fun tools(): Tools

    fun settings(): SettingsRepository
  }

  override suspend fun doWork(): Result =
    try {
      runDue()
      Result.success()
    } catch (stopped: CancellationException) {
      throw stopped
    } catch (failure: Throwable) {
      Log.w(TAG, "Standing tasks failed", failure)
      // Retried rather than failed: the usual reason is the model not being loadable right
      // now, and the next quarter of an hour is as good a time as any.
      Result.retry()
    }

  private suspend fun runDue() {
    val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
    val settings = deps.settings()
    val tasks = tasksFrom(settings.standingTasks.first())
    if (tasks.isEmpty()) return

    val now = LocalTime.now()
    val date = LocalDate.now()
    val due = dueNow(tasks, date.dayOfWeek.value, now.hour * 60 + now.minute, date.toEpochDay())
    if (due.isEmpty()) return

    val backend =
      (deps.backends().readiness() as? ChatReadiness.Ready)?.backend
      // Left unmarked so it runs at the next check rather than being lost for the day.
      ?: return

    var updated = tasks
    for (task in due) {
      val answer = ask(deps, backend, task) ?: continue
      notify(task, answer)
      updated = markRun(updated, task.id, date.toEpochDay())
      // Written after each one rather than at the end, so a task that answered is not run
      // again because a later one crashed the worker.
      settings.setStandingTasks(storedTasks(updated))
    }
  }

  /** One turn, with the tools, exactly as though the user had typed it. */
  private suspend fun ask(
    deps: Deps,
    backend: com.lin.agent.engine.LlmBackend,
    task: StandingTask,
  ): String? {
    val off = disabledFrom(deps.settings().disabledTools.first())
    val registry = deps.tools().registry.value
    // Delegation is withheld outright here. A standing task running in the background is the
    // worst place to spend a minute on a server round trip nobody is waiting through.
    val withheld = off + DelegateTool.NAME
    val offered = registry.forRole(AgentRole.ORCHESTRATOR).filterNot { it.name in withheld }

    val who = personaPrompt(personaFrom(deps.settings().persona.first()))
    val prompt = buildString {
      append("You are Lin, an assistant running on this phone. ")
      append("This question was asked by a standing routine rather than typed just now, so ")
      append("answer it in full and do not ask anything back - nobody is there to reply. ")
      if (who.isNotBlank()) append(who).append(' ')
      append(capabilityPrompt(offered))
    }

    val events =
      TurnLoop(
          backend = backend,
          registry = registry,
          role = AgentRole.ORCHESTRATOR,
          systemPrompt = prompt,
          withhold = withheld,
        )
        .run(listOf(Message(Message.Role.USER, task.ask)))
        .toList()

    return events.filterIsInstance<LoopEvent.Done>().lastOrNull()?.text?.takeIf { it.isNotBlank() }
  }

  private fun notify(task: StandingTask, answer: String) {
    if (
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
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
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(task.ask)
        .setContentText(answer.lineSequence().firstOrNull().orEmpty())
        // The whole answer, because the point of a routine is not having to open anything.
        .setStyle(NotificationCompat.BigTextStyle().bigText(answer))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build(),
    )
  }

  companion object {
    private const val TAG = "LinStanding"
    private const val CHANNEL = "standing"
    private const val WORK = "standing-tasks"

    /**
     * Starts the check, or stops it when there is nothing to check.
     *
     * Called whenever the list changes and at launch. Fifteen minutes is WorkManager's own
     * floor for periodic work, and it is the right order of magnitude: a routine set for
     * eight o'clock arriving by a quarter past is not a routine anybody would complain about.
     */
    fun sync(work: WorkManager, tasks: List<StandingTask>) {
      if (tasks.none { it.enabled }) {
        work.cancelUniqueWork(WORK)
        return
      }
      work.enqueueUniquePeriodicWork(
        WORK,
        // Kept rather than replaced: replacing it on every edit would restart the interval,
        // so somebody adjusting three routines in a row would push the next check away each
        // time they saved one.
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<StandingWorker>(15, TimeUnit.MINUTES).build(),
      )
    }
  }
}
