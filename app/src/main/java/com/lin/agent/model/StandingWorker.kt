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
import com.lin.agent.core.persona.personaOrDefault
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

    // The charging trigger waits for a plug-in once per unplug; this is where it is re-armed.
    if (tasks.any { it.enabled && it.trigger == com.lin.agent.core.standing.Trigger.CHARGING }) {
      Routines.armCharging(context)
    }

    val now = LocalTime.now()
    val date = LocalDate.now()
    val due = dueNow(tasks, date.dayOfWeek.value, now.hour * 60 + now.minute, date.toEpochDay())
    if (due.isEmpty()) return

    val runner = RoutineRunner(context)
    for (task in due) {
      // Null when nothing could answer: left unmarked so the next check tries again.
      runner.run(task) ?: continue
      // Re-read before writing, so an edit made while this ran is kept. Written after each one,
      // so a routine that answered is not run again because a later one crashed the worker.
      val latest = tasksFrom(settings.standingTasks.first())
      settings.setStandingTasks(storedTasks(markRun(latest, task.id, date.toEpochDay())))
    }
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
