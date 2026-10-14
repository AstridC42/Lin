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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lin.agent.core.voice.Launch
import com.lin.agent.core.voice.Summon
import com.lin.agent.ui.AssistPanel
import com.lin.agent.ui.theme.LinTheme
import com.lin.agent.voice.WakeService
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

/**
 * Lin when it is summoned rather than opened: a panel over whatever was already on screen.
 *
 * Its own activity rather than a mode of [MainActivity], and that is a platform constraint
 * rather than a preference. A window's theme is fixed when it is created, so one activity
 * cannot be both a full-screen app and a translucent panel; it would keep whichever window it
 * happened to be born with and the other way of opening Lin would be wrong from then on. The
 * attempt to do it in one activity is in the history of this file's manifest entry.
 *
 * What arrives here: the system's assistant intent (the power button, the gesture, the
 * assistant button on a car kit), and the wake word service. All three mean the same thing -
 * somebody spoke to a phone they are not holding a keyboard for - so all three open the
 * microphone, and one of them brings the question along with it.
 *
 * `singleTask`, so a second summon reuses this panel instead of stacking another on top; which
 * is why [onNewIntent] exists and why it calls `setIntent`. Without it the panel would keep
 * reading the intent it was created with and the second question would never be heard.
 */
@AndroidEntryPoint
class AssistActivity : ComponentActivity() {

  private var summon by mutableStateOf(Summoned())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The saved token wins over the intent's, which is the whole reason it is saved: this
    // activity is recreated when the device is folded, with the same intent redelivered, and
    // without it the question would be asked a second time by the act of unfolding.
    summon = summonedBy(intent, savedInstanceState?.getString(KEY_TOKEN))

    setContent {
      LinTheme {
        AssistPanel(
          launch = Launch.ASSIST,
          spoken = summon.spoken,
          token = summon.token,
          onClose = { finish() },
          // The one thing the panel cannot do. A download belongs on a screen somebody can
          // watch, not in a strip over their home screen.
          onGetModel = { openFullApp() },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // A new summon, so no saved token: this one has not been handled.
    summon = summonedBy(intent, saved = null)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(KEY_TOKEN, summon.token)
  }

  private fun summonedBy(intent: Intent?, saved: String?) =
    Summoned(
      token =
        Summon.tokenFor(
          saved = saved,
          // Minted by the wake word service, so that saying the same thing twice is two
          // summons. The system's assistant intent carries nothing of ours.
          fromIntent = intent?.getStringExtra(WakeService.EXTRA_TOKEN),
          fresh = UUID.randomUUID().toString(),
        ),
      spoken = intent?.getStringExtra(WakeService.EXTRA_SAID).orEmpty(),
    )

  private fun openFullApp() {
    startActivity(
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    // The panel goes. Leaving it behind the app it just opened would put it back on screen the
    // moment somebody pressed back.
    finish()
  }

  private companion object {
    const val KEY_TOKEN = "summon_token"
  }
}

/** One summon: which one it is, and whatever was already said. */
private data class Summoned(val token: String = "", val spoken: String = "")
