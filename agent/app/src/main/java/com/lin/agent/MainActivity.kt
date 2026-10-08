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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lin.agent.core.diag.CrashReport
import com.lin.agent.core.diag.LaunchHealth
import com.lin.agent.ui.ConversationScreen
import com.lin.agent.ui.RecoveryScreen
import com.lin.agent.ui.SettingsScreen
import com.lin.agent.ui.theme.LinTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The only activity.
 *
 * Note what is *not* here: the saved instance state is passed through to `super` rather than
 * discarded. The app this replaces deliberately passed null, to "start cleanly after an OS kill" -
 * which on a foldable means every fold and unfold throws the screen away mid-conversation. On a
 * Fold 7, going between an 8-inch and a 6.5-inch screen is a configuration change that happens
 * constantly, and surviving it is a requirement rather than a nicety.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    LaunchHealth.mark(this, "activity created")

    // Two launches in a row that never reached the screen. Show what was recorded and offer a way
    // out, built from a string and two lambdas so that nothing it needs can be the broken thing.
    if (LaunchHealth.inRecovery) {
      setContent {
        LinTheme {
          RecoveryScreen(
            report = CrashReport.read(this).orEmpty(),
            failures = LaunchHealth.consecutiveFailures,
            reachedPhase = LaunchHealth.lastMilestone(this),
            onStartNormally = {
              CrashReport.clear(this)
              LaunchHealth.clearFailures(this)
              // Ends the process rather than navigating: an ordinary start has to begin from
              // Application.onCreate, and this launch skipped it.
              finishAffinity()
              Runtime.getRuntime().exit(0)
            },
          )
        }
      }
      return
    }

    LaunchHealth.mark(this, "content set")
    setContent {
      LinTheme {
        // A boolean rather than a navigation graph. There are two screens; a navigation library
        // for two screens is ceremony, and rememberSaveable means the choice survives a fold.
        var showSettings by rememberSaveable { mutableStateOf(false) }
        if (showSettings) {
          SettingsScreen(onBack = { showSettings = false })
        } else {
          ConversationScreen(onOpenSettings = { showSettings = true })
        }
        // Recorded from inside the composition rather than at the end of onCreate: onCreate
        // returning only means the work was scheduled, so a crash in the first composition would
        // otherwise be recorded as a success and recovery would never trigger.
        LaunchedEffect(Unit) {
          LaunchHealth.mark(this@MainActivity, "first frame")
          LaunchHealth.noteLaunchSucceeded(this@MainActivity)
        }
      }
    }
  }
}
