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

package com.lin.agent.core.diag

import android.content.Context
import android.util.Log

/**
 * Whether the last launch reached the screen, and what to do when it did not.
 *
 * This exists in the first commit rather than the last, and it is worth saying why. The app this
 * one replaces spent a week unopenable on the only device that mattered: it crashed before the
 * first frame, which meant the crash report it had faithfully written could never be displayed,
 * and because the cause was on disk it did the same thing on every subsequent launch. From a phone
 * with no computer attached that is "it keeps crashing" and nothing more - no detail obtainable,
 * no way back in short of clearing the app's storage and losing a multi-gigabyte model with it.
 *
 * So the launch is checkpointed. A flag goes down as early as code can run and is lifted when the
 * first frame is actually on screen. A launch that sets the flag and never lifts it did not
 * survive; two of those in a row and the app comes up in recovery instead, showing what was
 * recorded and offering a way out.
 *
 * Two in a row rather than one, deliberately: a single unlifted flag is also exactly what
 * force-quitting during start-up looks like, and dropping somebody into a recovery screen because
 * they changed their mind about opening the app would be its own bug.
 *
 * SharedPreferences rather than DataStore, for three reasons that all matter here: this has to
 * work when the proto stores are the suspects, it has to be readable before dependency injection
 * has run, and it has to survive a process that is about to die - so `commit()`, not `apply()`.
 */
object LaunchHealth {

  /** Whether this launch is a recovery launch. Set once, early; read freely. */
  @Volatile
  var inRecovery: Boolean = false
    private set

  /** How many launches in a row failed to reach the first frame, including this one. */
  @Volatile
  var consecutiveFailures: Int = 0
    private set

  /** Called from `attachBaseContext`, before Hilt and before `onCreate`. */
  fun noteLaunchStarted(context: Context) {
    try {
      val prefs = prefs(context)
      val decision =
        decide(
          launchInProgress = prefs.getBoolean(KEY_IN_PROGRESS, false),
          recordedFailures = prefs.getInt(KEY_FAILURES, 0),
        )
      consecutiveFailures = decision.failures
      inRecovery = decision.recover
      prefs
        .edit()
        .putBoolean(KEY_IN_PROGRESS, true)
        .putInt(KEY_FAILURES, decision.failures)
        .commit()
      if (inRecovery) Log.w(TAG, "Recovery launch after ${decision.failures} failures")
    } catch (e: Throwable) {
      // Never the reason the app will not start. Without this it behaves as if absent.
      Log.e(TAG, "Could not record the start of this launch", e)
    }
  }

  /** Called once the first frame is on screen. */
  fun noteLaunchSucceeded(context: Context) {
    try {
      prefs(context).edit().putBoolean(KEY_IN_PROGRESS, false).putInt(KEY_FAILURES, 0).commit()
      consecutiveFailures = 0
    } catch (e: Throwable) {
      Log.e(TAG, "Could not record a successful launch", e)
    }
  }

  /** Forgets the failures, so the next launch is an ordinary one. The user's choice to make. */
  fun clearFailures(context: Context) {
    try {
      prefs(context).edit().putBoolean(KEY_IN_PROGRESS, false).putInt(KEY_FAILURES, 0).commit()
      consecutiveFailures = 0
      inRecovery = false
    } catch (e: Throwable) {
      Log.e(TAG, "Could not clear the failure count", e)
    }
  }

  /**
   * Records how far this launch got, overwriting the previous mark.
   *
   * For the crash that leaves no stack trace at all. When the system kills a process - out of
   * memory, or a native abort inside an inference engine - no Java code runs on the way down, so
   * the crash handler records nothing and the report comes back empty. A breadcrumb survives that,
   * because it was written before the process died rather than during it. "Got as far as the model
   * list" and "got as far as the first frame" are different bugs in different code.
   */
  fun mark(context: Context, milestone: String) {
    try {
      prefs(context).edit().putString(KEY_MILESTONE, milestone).commit()
    } catch (e: Throwable) {
      Log.e(TAG, "Could not record milestone '$milestone'", e)
    }
  }

  /** The furthest the previous launch got. */
  fun lastMilestone(context: Context): String =
    try {
      prefs(context).getString(KEY_MILESTONE, "").orEmpty()
    } catch (e: Throwable) {
      ""
    }

  /** How many launches have failed in a row, and whether that is enough to act on. */
  data class Decision(val failures: Int, val recover: Boolean)

  /**
   * The whole rule, with no Android in it, so it can be tested.
   *
   * This is the part that decides whether somebody whose app will not open gets a way back in, and
   * whether somebody whose app is fine is dropped into a recovery screen they never asked for.
   * Both directions are worth a test.
   */
  fun decide(launchInProgress: Boolean, recordedFailures: Int): Decision {
    if (!launchInProgress) return Decision(failures = 0, recover = false)
    // Coerced because this came off a disk that may be the thing misbehaving: a negative value
    // must not be able to subtract its way out of recovery.
    val failures = recordedFailures.coerceAtLeast(0) + 1
    return Decision(failures = failures, recover = failures >= FAILURES_BEFORE_RECOVERY)
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private const val PREFS_NAME = "lin_launch_health"
  private const val KEY_IN_PROGRESS = "launch_in_progress"
  private const val KEY_FAILURES = "consecutive_failures"
  private const val KEY_MILESTONE = "last_milestone"

  /** A force-quit during start-up looks like one failure, so one is not enough to act on. */
  const val FAILURES_BEFORE_RECOVERY = 2

  private const val TAG = "LinLaunchHealth"
}
