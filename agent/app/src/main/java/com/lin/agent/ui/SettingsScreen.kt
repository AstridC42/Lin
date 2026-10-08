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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
      for (model in state.models) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(
              if (model.present) model.name else "${model.name} — not downloaded",
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              model.detail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      // Until the download arrives in Phase 1b, the quickest way to get a real model in front of
      // this app is to copy one in - including the one the archived fork already downloaded.
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
