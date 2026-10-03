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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.WebSearchSettings
import com.google.ai.edge.gallery.net.LocalNetwork
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.search.WebSearchOutcome
import kotlinx.coroutines.launch

/**
 * Connects the assistant to a SearXNG instance.
 *
 * SearXNG is the only supported search backend on purpose: it is self-hosted, so the user's
 * queries never reach a commercial search API, which is the main privacy reason to replace Google
 * Assistant with an on-device one in the first place.
 *
 * Settings are written on Save. "Test connection" runs a real query against the URL currently in
 * the field, because the most common failure - an instance that only serves HTML - is invisible
 * until something actually asks it for JSON.
 */
@Composable
fun WebSearchSettingsDialog(
  dataStoreRepository: DataStoreRepository,
  searxngClient: SearxngClient,
  onDismiss: () -> Unit,
) {
  val stored = remember { dataStoreRepository.readWebSearchSettings() }
  val scope = rememberCoroutineScope()

  var instanceUrl by remember { mutableStateOf(stored.instanceUrl) }
  var enabled by remember { mutableStateOf(stored.enabled) }
  var allowPageFetch by remember {
    // Default to on for a fresh install: reading a result page is what makes search useful.
    mutableStateOf(if (stored.instanceUrl.isEmpty()) true else stored.allowPageFetch)
  }
  var maxResults by remember {
    mutableStateOf(
      (stored.maxResults.takeIf { it > 0 } ?: SearxngClient.DEFAULT_MAX_RESULTS).toString()
    )
  }
  var categories by remember {
    mutableStateOf(stored.categories.ifEmpty { SearxngClient.DEFAULT_CATEGORIES })
  }
  var language by remember { mutableStateOf(stored.language.ifEmpty { "auto" }) }
  var safeSearch by remember { mutableStateOf(stored.safeSearch.coerceIn(0, 2).toString()) }
  var timeoutSeconds by remember {
    mutableStateOf(
      (stored.timeoutSeconds.takeIf { it > 0 } ?: SearxngClient.DEFAULT_TIMEOUT_SECONDS).toString()
    )
  }
  var authHeader by remember { mutableStateOf(stored.authHeader) }
  var testStatus by remember { mutableStateOf<String?>(null) }
  var testing by remember { mutableStateOf(false) }

  fun currentSettings(): WebSearchSettings =
    WebSearchSettings.newBuilder()
      .setInstanceUrl(instanceUrl.trim())
      .setEnabled(enabled && instanceUrl.isNotBlank())
      .setAllowPageFetch(allowPageFetch)
      .setMaxResults(maxResults.toIntOrNull()?.coerceIn(1, 20) ?: SearxngClient.DEFAULT_MAX_RESULTS)
      .setCategories(categories.trim().ifEmpty { SearxngClient.DEFAULT_CATEGORIES })
      .setLanguage(language.trim().ifEmpty { "auto" })
      .setSafeSearch(safeSearch.toIntOrNull()?.coerceIn(0, 2) ?: 1)
      .setTimeoutSeconds(
        timeoutSeconds.toIntOrNull()?.coerceIn(5, 120) ?: SearxngClient.DEFAULT_TIMEOUT_SECONDS
      )
      .setAuthHeader(authHeader.trim())
      .build()

  val testOkTemplate = stringResource(R.string.web_search_test_ok)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.web_search_settings_title)) },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          stringResource(R.string.web_search_settings_intro),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
          value = instanceUrl,
          onValueChange = {
            instanceUrl = it
            testStatus = null
          },
          label = { Text(stringResource(R.string.web_search_instance_url)) },
          placeholder = { Text("https://searx.example.org") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth(),
        )

        // Said here rather than left to fail at the first search. A self-hosted instance is
        // normally plain http:// on the home network, Android refuses cleartext, and the error
        // that surfaced read like the instance was down - so the address looked wrong when it was
        // right.
        CleartextWarning(instanceUrl)

        SettingSwitchRow(
          label = stringResource(R.string.web_search_enable),
          checked = enabled,
          enabled = instanceUrl.isNotBlank(),
          onCheckedChange = { enabled = it },
        )

        SettingSwitchRow(
          label = stringResource(R.string.web_search_allow_page_fetch),
          checked = allowPageFetch,
          onCheckedChange = { allowPageFetch = it },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = maxResults,
            onValueChange = { maxResults = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.web_search_max_results)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = safeSearch,
            onValueChange = { safeSearch = it.filter(Char::isDigit).take(1) },
            label = { Text(stringResource(R.string.web_search_safe_search)) },
            supportingText = { Text("0-2") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
          )
        }

        OutlinedTextField(
          value = categories,
          onValueChange = { categories = it },
          label = { Text(stringResource(R.string.web_search_categories)) },
          placeholder = { Text("general,news") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = language,
            onValueChange = { language = it },
            label = { Text(stringResource(R.string.web_search_language)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = timeoutSeconds,
            onValueChange = { timeoutSeconds = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.web_search_timeout)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
          )
        }

        OutlinedTextField(
          value = authHeader,
          onValueChange = { authHeader = it },
          label = { Text(stringResource(R.string.web_search_auth_header)) },
          placeholder = { Text("Bearer …") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
          enabled = !testing && instanceUrl.isNotBlank(),
          onClick = {
            // Persist first: the client always reads settings from the DataStore, so a test has
            // to run against what was typed, not against what was saved last time.
            dataStoreRepository.saveWebSearchSettings(
              currentSettings().toBuilder().setEnabled(true).build()
            )
            testing = true
            testStatus = null
            scope.launch {
              testStatus =
                when (val outcome = searxngClient.search(query = "searxng", maxResults = 3)) {
                  is WebSearchOutcome.Success -> testOkTemplate.format(outcome.hits.size)
                  is WebSearchOutcome.Failure -> outcome.message
                }
              testing = false
            }
          },
        ) {
          Text(
            if (testing) stringResource(R.string.web_search_testing)
            else stringResource(R.string.web_search_test_connection)
          )
        }

        testStatus?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          dataStoreRepository.saveWebSearchSettings(currentSettings())
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          // Undo anything "Test connection" wrote before backing out.
          dataStoreRepository.saveWebSearchSettings(stored)
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
private fun SettingSwitchRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean = true,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

/**
 * Warns when an address cannot be fetched as typed.
 *
 * Two different problems wear the same `http://` prefix and need different answers from the user,
 * which is why they are told apart rather than lumped into one message. A plain address on the
 * home network is the ordinary way to run these services and the app is what has to change. A
 * plain address on the internet would put the instance's credentials on the wire in the clear,
 * and the user should change the address instead.
 */
@Composable
private fun CleartextWarning(url: String) {
  val typed = url.trim()
  if (!typed.startsWith("http://", ignoreCase = true)) return
  val host = typed.removePrefix("http://").substringBefore('/').substringBeforeLast(':')
  val onHomeNetwork = LocalNetwork.isPrivateHost(host)
  Text(
    if (onHomeNetwork) {
      "This build does not make plain http:// requests, so this address will not be reached. " +
        "Serve the instance over https://, or ask for cleartext to the home network to be " +
        "turned back on."
    } else {
      "$host is on the internet, so http:// would send your searches and any Authorization " +
        "header in the clear. Use https://."
    },
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
  )
}
