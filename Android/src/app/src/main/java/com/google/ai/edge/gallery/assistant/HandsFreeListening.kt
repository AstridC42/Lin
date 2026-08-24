/*
 * Copyright 2026 The Star Storyteller Authors
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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel

/** What the hands-free listening state looks like to the screen showing it. */
data class HandsFreeListeningState(
  val active: Boolean,
  val partialText: String,
  val cancel: () -> Unit,
)

/**
 * Opens the microphone once, for a summon that arrived with nobody able to touch the screen.
 *
 * The assist gesture, a headset button and a car's voice control all mean "I am talking to you
 * now". Landing on a chat with an empty text box would put the burden straight back on the user's
 * hands, which is the one thing those routes exist to avoid.
 *
 * Listening happens exactly once per summon, and once per turn of a hands-free conversation.
 * [alreadyHeard] is what makes that true across recomposition and across a rotation or a fold, so
 * the microphone does not reopen underneath a question that has already been asked; [turn] is what
 * re-arms it for the next question in a conversation.
 *
 * If `RECORD_AUDIO` has not been granted this asks for it, and does not listen if it is refused -
 * a refused microphone leaves the chat perfectly usable by hand.
 */
@Composable
fun rememberHandsFreeListening(
  enabled: Boolean,
  dictateViewModel: HoldToDictateViewModel,
  turn: Int = 0,
  alreadyHeard: Boolean,
  onHeard: (String) -> Unit,
): HandsFreeListeningState {
  val context = LocalContext.current
  val dictateState by dictateViewModel.uiState.collectAsState()

  // Re-keyed on the turn so each round of a hands-free conversation listens again. Without that,
  // the guard that makes a summon listen exactly once would also stop the second question.
  var started by remember(turn) { mutableStateOf(false) }
  var permissionAsked by remember { mutableStateOf(false) }
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      permissionGranted = granted
    }

  val shouldListen = enabled && !alreadyHeard && !started

  LaunchedEffect(shouldListen, permissionGranted) {
    if (!shouldListen) return@LaunchedEffect
    if (!permissionGranted) {
      if (!permissionAsked) {
        permissionAsked = true
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
      return@LaunchedEffect
    }
    started = true
    dictateViewModel.startSpeechRecognition(
      onDone = { text ->
        val spoken = text.trim()
        if (spoken.isNotEmpty()) onHeard(spoken)
      },
      onAmplitudeChanged = {},
    )
  }

  // Leaving the screen with the microphone open would keep recording behind whatever comes next.
  DisposableEffect(Unit) { onDispose { if (started) dictateViewModel.stopSpeechRecognition() } }

  return HandsFreeListeningState(
    active = dictateState.recognizing,
    partialText = dictateState.recognizedText,
    cancel = {
      dictateViewModel.stopSpeechRecognition()
      started = true
    },
  )
}

/** Shows that the microphone is open, and what has been heard so far. */
@Composable
fun ListeningDialog(heard: String, onCancel: () -> Unit) {
  AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(stringResource(R.string.assistant_listening_title)) },
    text = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Text(
          heard.ifEmpty { stringResource(R.string.assistant_listening_hint) },
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (heard.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onCancel) { Text(stringResource(R.string.assistant_listening_stop)) }
    },
  )
}
