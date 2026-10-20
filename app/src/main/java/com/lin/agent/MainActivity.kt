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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lin.agent.car.CarNotification
import com.lin.agent.core.diag.CrashReport
import com.lin.agent.core.diag.LaunchHealth
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.lin.agent.ui.ConversationScreen
import com.lin.agent.ui.DocumentsScreen
import com.lin.agent.voice.WakeService
import kotlinx.coroutines.flow.first
import dagger.hilt.android.EntryPointAccessors
import com.lin.agent.ui.BackupScreen
import com.lin.agent.ui.MemoryScreen
import com.lin.agent.ui.TrainingScreen
import com.lin.agent.ui.RecoveryScreen
import com.lin.agent.ui.SettingsScreen
import com.lin.agent.ui.theme.LinTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Lin opened rather than summoned: the whole app, with the conversation and the settings behind
 * it. Being summoned brings up a panel instead, which is its own activity for reasons stated in
 * [AssistActivity].
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

    // Summoned, but at the wrong door.
    //
    // Android stores the chosen assistant as a component name rather than as a package - a
    // phone told "Lin" before the panel existed still points at this activity by name, and an
    // update that moved the ASSIST filter elsewhere does not change what was stored. The
    // system then starts this activity explicitly, no filter needed, and the whole app opens
    // over whatever somebody was doing: the thing the panel was built to stop.
    //
    // So a summons that lands here is handed straight on. Re-picking Lin in the phone's
    // assistant settings makes the system point at the panel directly and skips this hop,
    // but nobody should have to know that.
    if (intent?.action in SUMMONS) {
      startActivity(
        Intent(this, AssistActivity::class.java).apply {
          action = intent?.action
          intent?.extras?.let { putExtras(it) }
          addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
      )
      finish()
      return
    }

    // Asked once, at launch, rather than at the moment a download starts: being interrupted by a
    // permission dialog in the middle of tapping Download is worse, and refusing it only hides
    // the progress - the download itself still runs.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        .launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchHealth.mark(this, "content set")
    setContent {
      LinTheme {
        // A saved string rather than a navigation graph. There are three screens now; a
        // navigation library for three is still ceremony, and rememberSaveable means the
        // choice survives a fold, which on this hardware happens constantly.
        // A document shared to Lin opens the Documents screen with it already added, rather
        // than the conversation - the share said what the user wanted to do, and landing in the
        // chat would leave them wondering whether it arrived.
        val shared =
          if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
          } else null
        var screen by rememberSaveable { mutableStateOf(if (shared != null) DOCUMENTS else CONVERSATION) }

        // Where the back arrow goes, and - the part that was missing - where the phone's own
        // back button goes. Without a BackHandler the system back finishes the activity, so
        // pressing it anywhere but the conversation closed the whole app instead of going up
        // one screen. One value for both, so they cannot drift apart.
        val backTo: String? =
          when (screen) {
            SETTINGS -> CONVERSATION
            SETUP -> CONVERSATION
            MEMORY, TRAINING, BACKUP -> SETTINGS
            // A share has no settings screen behind it, so back goes where the user would
            // otherwise have been.
            DOCUMENTS -> if (shared != null) CONVERSATION else SETTINGS
            // The conversation is the bottom of the stack: back there means leaving, which is
            // what the system does on its own.
            else -> null
          }
        BackHandler(enabled = backTo != null) { backTo?.let { screen = it } }

        when (screen) {
          SETUP -> com.lin.agent.ui.SetupScreen(onDone = { screen = CONVERSATION })
          SETTINGS ->
            SettingsScreen(
              onBack = { screen = CONVERSATION },
              onOpenSetup = { screen = SETUP },
              onOpenMemory = { screen = MEMORY },
              onOpenDocuments = { screen = DOCUMENTS },
              onOpenTraining = { screen = TRAINING },
              onOpenBackup = { screen = BACKUP },
            )
          // Back goes to settings, not the conversation: that is where it was opened from, and
          // landing somewhere else after deleting something reads as the app losing its place.
          MEMORY -> MemoryScreen(onBack = { screen = SETTINGS })
          TRAINING -> TrainingScreen(onBack = { screen = SETTINGS })
          BACKUP -> BackupScreen(onBack = { screen = SETTINGS })
          DOCUMENTS ->
            DocumentsScreen(
              // A share has no settings screen behind it, so back goes where the user would
              // otherwise have been.
              onBack = { screen = if (shared != null) CONVERSATION else SETTINGS },
              incoming = shared,
            )
          else ->
            // No launch to read here, and that is the point: every way of being summoned - the
            // power button, a car kit's voice button, the wake word - now brings up the panel
            // instead, which is where the microphone opens on its own. This screen is only ever
            // opened deliberately, and an app that started recording because somebody tapped
            // its icon would be a surprise of exactly the kind an assistant has to avoid.
            ConversationScreen(
              onOpenSettings = { screen = SETTINGS },
              // The empty state's button goes to the same screen the gear does, which is where
              // the downloads live. One place to manage models, not two.
              onGetModel = { screen = SETTINGS },
            )
        }
        // Recorded from inside the composition rather than at the end of onCreate: onCreate
        // returning only means the work was scheduled, so a crash in the first composition would
        // otherwise be recorded as a success and recovery would never trigger.
        LaunchedEffect(Unit) {
          LaunchHealth.mark(this@MainActivity, "first frame")
          LaunchHealth.noteLaunchSucceeded(this@MainActivity)

          // Listening restarts here rather than at boot, and that is a platform constraint
          // rather than a choice: from Android 14 a microphone foreground service cannot be
          // started from the background, so a BOOT_COMPLETED receiver would throw rather than
          // listen. An activity on screen is the one place it is allowed. The cost is that
          // after a restart, Lin has to be opened once.
          val settings =
            EntryPointAccessors.fromApplication(
                this@MainActivity.applicationContext,
                SettingsEntryPoint::class.java,
              )
              .settings()
          // First launch, or the first after this update: walk through what Lin needs.
          if (shared == null && !settings.setupDone.first()) screen = SETUP
          if (settings.wakeWord.first()) WakeService.start(this@MainActivity)
          // Put the car notification back after a restart. It is how listening is started
          // without opening the app, so it has to outlive the app being closed - and a
          // notification does not survive a reboot on its own.
          CarNotification.show(this@MainActivity, settings.carNotification.first())
          // The car conversation needs a message to reply to; put the greeting back if it went.
          if (settings.carChat.first() && !com.lin.agent.car.CarChat.isShowing(this@MainActivity)) {
            com.lin.agent.car.CarChat.greet(this@MainActivity)
          }
        }
      }
    }
  }
}

/**
 * Every way of being summoned rather than opened.
 *
 * Listed here because this activity should never answer any of them - see the hand-off in
 * onCreate. They are the panel's, and the panel's filters are the ones in the manifest.
 */
private val SUMMONS =
  setOf(
    Intent.ACTION_ASSIST,
    "android.intent.action.VOICE_COMMAND",
    "android.speech.action.VOICE_SEARCH_HANDS_FREE",
    WakeService.ACTION_WOKEN,
  )

/** The screens, as saveable strings. See the note where they are switched on. */
private const val CONVERSATION = "conversation"
private const val SETTINGS = "settings"
private const val MEMORY = "memory"
private const val DOCUMENTS = "documents"
private const val TRAINING = "training"
private const val BACKUP = "backup"
private const val SETUP = "setup"

/** Reading one setting from an activity, without making the activity a view model's owner. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SettingsEntryPoint {
  fun settings(): com.lin.agent.core.settings.SettingsRepository
}
