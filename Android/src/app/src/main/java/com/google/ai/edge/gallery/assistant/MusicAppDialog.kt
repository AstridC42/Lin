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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.media.MusicApps

/**
 * Which app "play something" goes to.
 *
 * Android has no default-music-app setting for an app to read - the one people picture does not
 * exist - so without a choice made here, asking for music opens a chooser every single time. That
 * is fine once and unbearable as the way an assistant works.
 *
 * The list is whatever on this phone answers a request to play something by name, rather than a
 * list of the music services someone thought of, so an obscure player is offered exactly like the
 * famous ones.
 */
@Composable
fun MusicAppDialog(dataStoreRepository: DataStoreRepository, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val apps = remember { MusicApps.installed(context) }
  var chosen by remember {
    mutableStateOf(dataStoreRepository.readAssistantSettings().preferredMusicApp)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.music_app_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (apps.isEmpty()) {
          Text(
            stringResource(R.string.music_app_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            stringResource(R.string.music_app_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            item {
              MusicAppRow(
                label = stringResource(R.string.music_app_ask),
                selected = chosen.isEmpty(),
                onClick = { chosen = "" },
              )
            }
            items(apps, key = { it.packageName }) { app ->
              MusicAppRow(
                label = app.label,
                selected = chosen == app.packageName,
                onClick = { chosen = app.packageName },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          dataStoreRepository.saveAssistantSettings(
            dataStoreRepository
              .readAssistantSettings()
              .toBuilder()
              .setPreferredMusicApp(chosen)
              .build()
          )
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
private fun MusicAppRow(label: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
  }
}
