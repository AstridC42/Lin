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

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R

/**
 * A copy of what cannot be got back.
 *
 * Plain about what is and is not in it. A backup that silently leaves out the models is fine;
 * a backup somebody believes contains them, discovered on a new phone, is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.restore(it, context) }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.backup_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
          }
        },
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(R.string.backup_explain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            stringResource(R.string.backup_holds, state.memories, state.packs),
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            stringResource(R.string.backup_not_included),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

      OutlinedButton(
        onClick = { viewModel.save(context) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.backup_save))
      }

      if (state.ready != null) {
        OutlinedButton(
          onClick = {
            viewModel.shareIntent(context)?.let { intent ->
              context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.backup_send))
              )
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.backup_send))
        }
      }

      OutlinedButton(
        // Zip, and octet-stream too: a backup that has been through a messaging app or a
        // cloud drive often comes back with no type at all, and a picker that will not show
        // it makes the backup useless at the moment it is needed.
        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.backup_restore))
      }

      Text(
        stringResource(R.string.backup_restore_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (state.said.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(state.said, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.backup_refresh)) }
    }
  }
}
