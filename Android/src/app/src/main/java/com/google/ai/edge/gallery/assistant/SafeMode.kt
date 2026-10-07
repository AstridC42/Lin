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

package com.google.ai.edge.gallery.assistant

import android.content.Context
import android.util.Log

/**
 * A way back in when the app cannot finish starting.
 *
 * The app already records the exception that killed it and shows it on the next launch, before it
 * will load anything. That covers a crash during a conversation. It does not cover a crash during
 * start-up, and the difference is the whole problem: the report is written to a file that only the
 * normal UI knows how to display, so a launch that dies before the first frame shows nothing,
 * offers nothing, and - because whatever caused it is on the disk - does exactly the same thing the
 * next time. Reported from a phone, that is "it keeps crashing non-stop" with no further detail
 * available, and the only escape is to clear the app's storage, which also deletes a model download
 * measured in gigabytes.
 *
 * So the launch itself is checkpointed. A flag goes down as early as code can run, and is lifted
 * when the first frame reaches the screen. A launch that starts and never lifts the flag did not
 * survive; two of those in a row and the app starts in safe mode instead - a single screen with no
 * view model, no navigation graph, no model and no settings store behind it, showing the recorded
 * crash and offering the few destructive things that might clear it, one at a time, instead of all
 * at once.
 *
 * Two in a row rather than one on purpose. A single failed launch is also what the user force-
 * quitting the app during start-up looks like, and dropping somebody into a recovery screen
 * because they changed their mind about opening the app would be its own bug.
 *
 * [SharedPreferences] rather than the proto stores everything else uses. This has to work when
 * those are the suspects, it has to be readable before Hilt has injected anything, and it has to
 * survive a process that is about to die - so it is the simplest, oldest thing available, written
 * with `commit()` so the value is on disk before the call returns.
 */
object SafeMode {

  /**
   * Whether this launch is a recovery launch. Read freely; set once, early.
   *
   * Held in memory rather than re-read because everything that asks runs after [noteLaunchStarted]
   * and must agree: a start-up step that skipped itself and a screen that decided to render
   * normally would be the worst of both.
   */
  @Volatile
  var active: Boolean = false
    private set

  /** How many launches in a row failed to reach the first frame, as of this launch. */
  @Volatile
  var consecutiveFailures: Int = 0
    private set

  /**
   * Called as early as the process allows - from `attachBaseContext`, before Hilt injects anything
   * and before `Application.onCreate` runs, so that a crash in either is still counted.
   */
  fun noteLaunchStarted(context: Context) {
    try {
      val prefs = prefs(context)
      val decision =
        decide(
          launchInProgress = prefs.getBoolean(KEY_IN_PROGRESS, false),
          recordedFailures = prefs.getInt(KEY_FAILURES, 0),
        )
      consecutiveFailures = decision.failures
      active = decision.safeMode
      prefs
        .edit()
        .putBoolean(KEY_IN_PROGRESS, true)
        .putInt(KEY_FAILURES, decision.failures)
        // commit, not apply: the process may not live long enough for an asynchronous write, and
        // a launch that is not recorded as started is a crash that does not count.
        .commit()
      if (active) Log.w(TAG, "Starting in safe mode after ${decision.failures} failed launches")
    } catch (e: Throwable) {
      // Never the reason the app will not start. Without this the app simply behaves as before.
      Log.e(TAG, "Could not record the start of this launch", e)
    }
  }

  /** How many launches have failed in a row, and whether that is enough to act on. */
  data class Decision(val failures: Int, val safeMode: Boolean)

  /**
   * The whole rule, with no Android in it.
   *
   * Split out to be testable. Everything around it is SharedPreferences and a Log call, but this
   * is the part that decides whether somebody whose app will not open gets a way back in or gets
   * nothing - and whether somebody whose app is fine is dropped into a recovery screen they never
   * asked for. Both of those are worth a test.
   *
   * [launchInProgress] is the flag from the previous launch: still set means that launch recorded
   * its start and never recorded reaching the screen.
   */
  fun decide(launchInProgress: Boolean, recordedFailures: Int): Decision {
    // A launch that completed clears the count, so anything other than an in-progress flag is a
    // clean slate - including a first ever launch, where there is nothing recorded at all.
    if (!launchInProgress) return Decision(failures = 0, safeMode = false)
    // Guard against a negative or absurd stored value: this is read from disk, and a count that
    // cannot be trusted should not be able to keep the app out of safe mode.
    val failures = recordedFailures.coerceAtLeast(0) + 1
    return Decision(failures = failures, safeMode = failures >= FAILURES_BEFORE_SAFE_MODE)
  }

  /**
   * Records how far this launch got, overwriting the previous mark.
   *
   * The point of this is the crash that leaves no stack trace. If the process is killed by the
   * system rather than by an exception - running out of memory, a native abort in the inference
   * engine - no Java code runs on the way down, so the crash reporter records nothing and the
   * report is empty. An empty report and a phone that shows nothing is the position this whole
   * investigation has been stuck in.
   *
   * A breadcrumb survives that, because it was written before the process died rather than during
   * it. "Got as far as loading the model list" and "got as far as the first frame" are different
   * bugs, and knowing which is most of the work of finding it.
   *
   * Cheap on purpose - a short string committed to SharedPreferences - because it is on the
   * start-up path and the start-up path is what is broken.
   */
  fun mark(context: Context, milestone: String) {
    try {
      prefs(context).edit().putString(KEY_MILESTONE, milestone).commit()
    } catch (e: Throwable) {
      Log.e(TAG, "Could not record milestone '$milestone'", e)
    }
  }

  /** The furthest the previous launch got, for showing alongside the report. */
  fun lastMilestone(context: Context): String =
    try {
      prefs(context).getString(KEY_MILESTONE, "").orEmpty()
    } catch (e: Throwable) {
      Log.e(TAG, "Could not read the last milestone", e)
      ""
    }

  /** Called once the first frame is on the screen: this launch worked. */
  fun noteLaunchSucceeded(context: Context) {
    try {
      prefs(context).edit().putBoolean(KEY_IN_PROGRESS, false).putInt(KEY_FAILURES, 0).commit()
      consecutiveFailures = 0
    } catch (e: Throwable) {
      Log.e(TAG, "Could not record a successful launch", e)
    }
  }

  /**
   * Forgets the failures, so the next launch is a normal one.
   *
   * Called when the user chooses to try a normal start from the recovery screen. Leaving safe mode
   * has to be their decision: if the next launch dies too, the count simply builds again and they
   * are back here.
   */
  fun clearFailures(context: Context) {
    try {
      prefs(context).edit().putInt(KEY_FAILURES, 0).putBoolean(KEY_IN_PROGRESS, false).commit()
      consecutiveFailures = 0
      active = false
    } catch (e: Throwable) {
      Log.e(TAG, "Could not clear the failure count", e)
    }
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private const val PREFS_NAME = "lin_launch_health"
  private const val KEY_IN_PROGRESS = "launch_in_progress"
  private const val KEY_FAILURES = "consecutive_failures"
  private const val KEY_MILESTONE = "last_milestone"

  /** A force-quit during start-up looks like one failure, so one is not enough to act on. */
  private const val FAILURES_BEFORE_SAFE_MODE = 2

  private const val TAG = "AGSafeMode"
}
