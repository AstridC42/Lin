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

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.standing.RoutineEvent
import com.lin.agent.core.standing.StandingTask
import com.lin.agent.core.standing.Trigger
import com.lin.agent.core.standing.firedBy
import com.lin.agent.core.standing.markEventRun
import com.lin.agent.core.standing.storedTasks
import com.lin.agent.core.standing.tasksFrom
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * Keeps the routines' triggers in step with the list: the clock check, the charging watch, and
 * the Bluetooth receiver, which is switched off entirely when no routine listens for it.
 */
object Routines {

  private const val CHARGE_WORK = "routine-charging"
  private const val PREFS = "routines"
  private const val ARMED = "charging_armed"

  fun sync(context: Context, tasks: List<StandingTask>) {
    val work = WorkManager.getInstance(context)
    StandingWorker.sync(work, tasks)

    val bluetooth = tasks.any { it.enabled && (it.trigger == Trigger.BLUETOOTH_CONNECTED || it.trigger == Trigger.BLUETOOTH_DISCONNECTED) }
    runCatching {
      context.packageManager.setComponentEnabledSetting(
        ComponentName(context, BluetoothRoutineReceiver::class.java),
        if (bluetooth) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
      )
    }

    val charging = tasks.any { it.enabled && it.trigger == Trigger.CHARGING }
    if (!charging) {
      work.cancelUniqueWork(CHARGE_WORK)
      prefs(context).edit().putBoolean(ARMED, false).apply()
    } else {
      armCharging(context)
    }
  }

  /**
   * Waits for the phone to be plugged in. Not armed while it is already charging, so creating a
   * "when charging" routine at the desk does not fire it at once; the clock check arms it again
   * once the phone is unplugged.
   */
  fun armCharging(context: Context) {
    if (isCharging(context)) return
    if (prefs(context).getBoolean(ARMED, false)) return
    WorkManager.getInstance(context)
      .enqueueUniqueWork(
        CHARGE_WORK,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<RoutineEventWorker>()
          .setInputData(workDataOf(RoutineEventWorker.KEY_EVENT to RoutineEventWorker.CHARGING))
          .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
          .build(),
      )
    prefs(context).edit().putBoolean(ARMED, true).apply()
  }

  internal fun disarmCharging(context: Context) = prefs(context).edit().putBoolean(ARMED, false).apply()

  fun isCharging(context: Context): Boolean =
    runCatching { context.getSystemService(BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)

  /** Runs one routine now, from the settings screen's Try button. */
  fun runNow(context: Context, id: String) {
    WorkManager.getInstance(context)
      .enqueue(
        OneTimeWorkRequestBuilder<RoutineEventWorker>()
          .setInputData(workDataOf(RoutineEventWorker.KEY_EVENT to RoutineEventWorker.NOW, RoutineEventWorker.KEY_ID to id))
          .build()
      )
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Runs the routines one event starts. */
class RoutineEventWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun settings(): SettingsRepository
  }

  override suspend fun doWork(): Result {
    val settings = EntryPointAccessors.fromApplication(context, Deps::class.java).settings()
    val tasks = tasksFrom(settings.standingTasks.first())
    val now = System.currentTimeMillis()
    val device = inputData.getString(KEY_DEVICE).orEmpty()
    val due =
      when (inputData.getString(KEY_EVENT)) {
        NOW -> tasks.filter { it.id == inputData.getString(KEY_ID) }
        CHARGING -> {
          Routines.disarmCharging(context)
          firedBy(RoutineEvent.StartedCharging, tasks, LocalDate.now().dayOfWeek.value, now)
        }
        CONNECTED -> firedBy(RoutineEvent.BluetoothConnected(device), tasks, LocalDate.now().dayOfWeek.value, now)
        DISCONNECTED -> firedBy(RoutineEvent.BluetoothDisconnected(device), tasks, LocalDate.now().dayOfWeek.value, now)
        else -> emptyList()
      }
    if (due.isEmpty()) return Result.success()

    val runner = RoutineRunner(context)
    for (task in due) {
      val answer = runCatching { runner.run(task) }.onFailure { Log.w(TAG, "Routine ${task.id} failed", it) }.getOrNull()
      if (answer != null) {
        // Re-read and merge only this field, so an edit made meanwhile is not overwritten.
        val latest = tasksFrom(settings.standingTasks.first())
        settings.setStandingTasks(storedTasks(markEventRun(latest, task.id, now)))
      }
    }
    return Result.success()
  }

  companion object {
    private const val TAG = "LinRoutineEvent"
    const val KEY_EVENT = "event"
    const val KEY_DEVICE = "device"
    const val KEY_ID = "id"
    const val CHARGING = "charging"
    const val CONNECTED = "bt_connected"
    const val DISCONNECTED = "bt_disconnected"
    const val NOW = "now"
  }
}

/**
 * Bluetooth connecting and disconnecting. These broadcasts are among the few Android still
 * delivers to an app that is not running, which is what lets "when I get in the car" work.
 * Disabled in the manifest and switched on by [Routines.sync] only when a routine needs it.
 */
class BluetoothRoutineReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val event =
      when (intent.action) {
        BluetoothDevice.ACTION_ACL_CONNECTED -> RoutineEventWorker.CONNECTED
        BluetoothDevice.ACTION_ACL_DISCONNECTED -> RoutineEventWorker.DISCONNECTED
        else -> return
      }
    val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    // The name needs the Nearby devices permission; without it the routine can still match
    // "any device".
    val allowed = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val name = if (allowed) runCatching { device?.alias ?: device?.name }.getOrNull().orEmpty() else ""
    WorkManager.getInstance(context)
      .enqueue(
        OneTimeWorkRequestBuilder<RoutineEventWorker>()
          .setInputData(workDataOf(RoutineEventWorker.KEY_EVENT to event, RoutineEventWorker.KEY_DEVICE to name))
          .build()
      )
  }
}
