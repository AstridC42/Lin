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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.voice.DEFAULT_WAKE_PHRASE
import com.google.ai.edge.gallery.voice.WakeWordService
import com.google.ai.edge.gallery.voice.WakeWordSettings

/**
 * Turns continuous listening on and off, and sets the phrase.
 *
 * The cost is stated on the dialog rather than buried: this holds the microphone open, so it uses
 * more battery than anything else the app does and Android shows the microphone indicator the
 * whole time. Someone deciding whether to leave it running should be able to see that while
 * deciding, not discover it afterwards from a battery screen.
 */
@Composable
fun WakeWordSettingsDialog(dataStoreRepository: DataStoreRepository, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val stored = remember { dataStoreRepository.readAssistantSettings() }

  var enabled by remember { mutableStateOf(stored.wakeWordEnabled) }
  var phrase by remember { mutableStateOf(WakeWordSettings.phraseOrDefault(stored.wakeWordPhrase)) }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      // Turning the switch back off on a refusal keeps the UI honest: a switch left on while
      // nothing can listen is worse than no switch.
      enabled = granted
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.wake_word_settings_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(stringResource(R.string.wake_word_enable), style = MaterialTheme.typography.bodyLarge)
          Switch(
            checked = enabled,
            onCheckedChange = { wanted ->
              if (
                wanted &&
                  ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
              ) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              } else {
                enabled = wanted
              }
            },
          )
        }

        OutlinedTextField(
          value = phrase,
          onValueChange = { phrase = it },
          label = { Text(stringResource(R.string.wake_word_phrase_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Text(
          stringResource(R.string.wake_word_cost_warning),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val chosen = phrase.trim().ifEmpty { DEFAULT_WAKE_PHRASE }
          dataStoreRepository.saveAssistantSettings(
            dataStoreRepository
              .readAssistantSettings()
              .toBuilder()
              .setWakeWordEnabled(enabled)
              .setWakeWordPhrase(chosen)
              .build()
          )
          // Restart rather than leave the old phrase running: the service reads its phrase once,
          // when it starts.
          WakeWordService.stop(context)
          if (enabled) WakeWordService.start(context, chosen)
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
