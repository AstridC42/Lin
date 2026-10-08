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

package com.lin.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.core.models.DownloadState
import com.lin.agent.R

/**
 * Settings, which for now is the server address and what the phone can run.
 *
 * The address is a single free-text URL rather than a host and a port, because it has to accept
 * both ways this server gets reached: a LAN address at home, and a Cloudflare tunnel hostname
 * from outside. Those differ in scheme, port and host shape.
 *
 * The header field is here from the start rather than added later, and it is worth saying why: a
 * tunnel puts the server on the public internet, and Ollama has no authentication of its own.
 * Without something in front of it, anyone who learns the hostname can use the GPU and read the
 * prompts. This is where a Cloudflare Access service token or a bearer token goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.back),
            )
          }
        },
      )
    },
    modifier = Modifier.imePadding(),
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(horizontal = 20.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(R.string.settings_server_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
      )
      Text(
        stringResource(R.string.settings_server_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = state.ollamaUrl,
        onValueChange = viewModel::onUrlChanged,
        label = { Text(stringResource(R.string.settings_server_url)) },
        placeholder = { Text("http://192.168.1.50:11434") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.ollamaAuthHeader,
        onValueChange = viewModel::onAuthHeaderChanged,
        label = { Text(stringResource(R.string.settings_server_header)) },
        placeholder = { Text("Authorization: Bearer ...") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        stringResource(R.string.settings_server_header_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = viewModel::save) { Text(stringResource(R.string.settings_save)) }
        if (state.saved) {
          Text(
            stringResource(R.string.settings_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp),
          )
        }
      }

      Text(
        stringResource(R.string.settings_models_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_device, state.memoryGb, state.socModel.ifEmpty { "?" }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      for (line in state.models) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(line.name, style = MaterialTheme.typography.bodyLarge)
            Text(
              line.detail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val download = line.download) {
              DownloadState.Waiting ->
                // Indeterminate, because nothing is moving yet. Showing a bar stuck at zero is
                // how a queued download gets mistaken for a broken one.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Text(
                      stringResource(R.string.download_waiting),
                      style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { viewModel.cancelDownload(line.model) }) {
                      Text(stringResource(R.string.download_stop))
                    }
                  }
                }
              is DownloadState.Running -> {
                // Determinate where the total is known, which it is from the catalogue. A
                // spinner for a twenty-minute transfer tells the user nothing.
                LinearProgressIndicator(
                  progress = { download.percent / 100f },
                  modifier = Modifier.fillMaxWidth(),
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    stringResource(
                      R.string.download_progress,
                      download.percent,
                      "%.1f".format(download.receivedBytes / 1_000_000_000.0),
                      "%.1f".format(download.totalBytes / 1_000_000_000.0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                  )
                  TextButton(onClick = { viewModel.cancelDownload(line.model) }) {
                    Text(stringResource(R.string.download_stop))
                  }
                }
              }
              is DownloadState.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(
                    download.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                  )
                  // Resuming, not restarting: whatever arrived is still on disk.
                  Button(onClick = { viewModel.download(line.model) }) {
                    Text(stringResource(R.string.download_retry))
                  }
                }
              else ->
                if (line.present) {
                  Text(
                    stringResource(R.string.download_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                } else {
                  Button(onClick = { viewModel.download(line.model) }) {
                    Text(stringResource(R.string.download_start))
                  }
                }
            }
          }
        }
      }
      // Still shown now that downloading works, because copying a file in is the one way to
      // avoid spending two and a half gigabytes of somebody's allowance twice.
      Text(
        stringResource(R.string.settings_models_where),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        state.modelsDirectory,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 32.dp),
      )
    }
  }
}
