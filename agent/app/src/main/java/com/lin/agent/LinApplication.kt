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

package com.lin.agent

import android.app.Application
import android.content.Context
import android.util.Log
import com.lin.agent.core.diag.CrashReport
import com.lin.agent.core.diag.LaunchHealth
import dagger.hilt.android.HiltAndroidApp

/**
 * Start-up, arranged so that no single step can stop the app opening.
 *
 * A throw from `onCreate` is the worst crash an Android app has: it happens before any UI exists,
 * so nothing can show what went wrong and nothing can offer a way out, and every step here reads
 * from disk - so the next launch does the same thing. That is the failure mode this whole app was
 * restructured to avoid, and the arrangement is deliberate rather than defensive habit.
 */
@HiltAndroidApp
class LinApplication : Application() {

  /** The earliest point our code runs: before Hilt injects, before [onCreate]. */
  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    LaunchHealth.noteLaunchStarted(base)
    LaunchHealth.mark(base, "process created")
  }

  override fun onCreate() {
    super.onCreate()
    // First, so a crash anywhere after this is recorded.
    CrashReport.install(this)

    if (LaunchHealth.inRecovery) {
      Log.w(TAG, "Recovery launch: skipping start-up work")
      return
    }
    LaunchHealth.mark(this, "application created")
  }

  private companion object {
    const val TAG = "LinApplication"
  }
}
