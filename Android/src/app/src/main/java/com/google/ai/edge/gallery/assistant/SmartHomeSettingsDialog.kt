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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.smarthome.HomeAssistantClient
import com.google.ai.edge.gallery.smarthome.HomeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a Home Assistant instance is entered.
 *
 * The test button matters more than it looks. Three things go wrong here and none of them announce
 * themselves: a `.local` address that only resolves on the home network, a token pasted with a
 * newline, and an instance that is simply asleep. Without a test, all three surface later as the
 * assistant saying it could not find a device - which reads as the feature being broken rather
 * than the address being wrong.
 */
@Composable
fun SmartHomeSettingsDialog(
  dataStoreRepository: DataStoreRepository,
  client: HomeAssistantClient,
  onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val stored = remember { dataStoreRepository.readSmartHomeSettings() }

  var baseUrl by remember { mutableStateOf(stored.baseUrl) }
  var token by remember { mutableStateOf(stored.accessToken) }
  var enabled by remember { mutableStateOf(stored.enabled) }
  var testResult by remember { mutableStateOf("") }
  var testing by remember { mutableStateOf(false) }

  fun persist() {
    dataStoreRepository.saveSmartHomeSettings(
      stored
        .toBuilder()
        .setBaseUrl(baseUrl.trim())
        .setAccessToken(token.trim())
        .setEnabled(enabled)
        .build()
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.smart_home_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          stringResource(R.string.smart_home_explainer),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
          value = baseUrl,
          onValueChange = { baseUrl = it },
          label = { Text(stringResource(R.string.smart_home_url_label)) },
          placeholder = { Text("http://homeassistant.local:8123") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          label = { Text(stringResource(R.string.smart_home_token_label)) },
          singleLine = true,
          // A long-lived token is a permanent key to someone's house; it should not sit in plain
          // text on screen while the dialog is open.
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(stringResource(R.string.smart_home_enable))
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        OutlinedButton(
          onClick = {
            // Saved before testing, because the client reads its settings from storage rather
            // than from these fields - testing the old address would be worse than not testing.
            persist()
            testing = true
            testResult = ""
            scope.launch {
              val outcome = withContext(Dispatchers.IO) { client.listEntities() }
              testing = false
              testResult =
                when (outcome) {
                  is HomeOutcome.Entities ->
                    if (outcome.entities.isEmpty()) {
                      "Connected, but no controllable devices were found."
                    } else {
                      "Connected. ${outcome.entities.size} device(s) found."
                    }
                  is HomeOutcome.Failed -> outcome.message
                  is HomeOutcome.NotConfigured ->
                    "Fill in the address and token, and turn this on."
                  is HomeOutcome.Called -> "Unexpected response."
                }
            }
          },
          enabled = !testing,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            stringResource(
              if (testing) R.string.smart_home_testing else R.string.smart_home_test
            )
          )
        }

        if (testResult.isNotEmpty()) {
          Text(
            testResult,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          persist()
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
