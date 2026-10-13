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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.core.training.TrainingData

/**
 * The corpus Lin is collecting, and how to take it away.
 *
 * Says plainly what is being kept, because a screen that only showed a count would be hiding
 * the part that matters: this is everything said to Lin, in readable text, including whatever a
 * tool returned on the way to an answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(onBack: () -> Unit, viewModel: TrainingViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // Packs are written from the conversation screen, so this list changes while nobody is
  // looking at it.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  if (state.confirmingClear) {
    AlertDialog(
      onDismissRequest = viewModel::cancelClear,
      title = { Text(stringResource(R.string.training_clear)) },
      text = { Text(stringResource(R.string.training_clear_detail, state.packs.size, state.pending)) },
      confirmButton = {
        TextButton(onClick = viewModel::clearEverything) {
          Text(stringResource(R.string.training_clear_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::cancelClear) { Text(stringResource(R.string.memory_cancel)) }
      },
    )
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.training_title)) },
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
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        stringResource(R.string.training_explain, TrainingData.PACK_SIZE),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.training_keep), style = MaterialTheme.typography.bodyLarge)
            Text(
              stringResource(R.string.training_keep_detail),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = state.keeping, onCheckedChange = viewModel::setKeeping)
        }
      }

      // The countdown, because "every thirty" is otherwise invisible until a file appears.
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            stringResource(R.string.training_pending, state.pending, TrainingData.PACK_SIZE),
            style = MaterialTheme.typography.bodyLarge,
          )
          LinearProgressIndicator(
            progress = {
              (state.pending.toFloat() / TrainingData.PACK_SIZE).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            stringResource(R.string.training_until_next, state.untilNextPack),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (state.packs.isNotEmpty()) {
        Text(
          stringResource(R.string.training_packs, state.packs.size, state.totalBytes / 1024),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
      } else {
        Text(
          stringResource(R.string.training_no_packs, TrainingData.PACK_SIZE),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }

      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(state.packs, key = { it.name }) { pack ->
          Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(pack.name, style = MaterialTheme.typography.bodyLarge)
                  Text(
                    stringResource(
                      R.string.training_pack_detail,
                      pack.conversations,
                      pack.sizeBytes / 1024,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(onClick = { viewModel.delete(pack) }) {
                  Icon(Icons.Filled.Delete, stringResource(R.string.training_delete_pack))
                }
              }
              TextButton(
                onClick = {
                  viewModel.shareIntent(pack, context)?.let { intent ->
                    context.startActivity(
                      Intent.createChooser(intent, context.getString(R.string.training_send))
                    )
                  }
                }
              ) {
                Text(stringResource(R.string.training_send))
              }
            }
          }
        }
      }

      if (state.packs.isNotEmpty() || state.pending > 0) {
        OutlinedButton(
          onClick = viewModel::askToClear,
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
          Text(stringResource(R.string.training_clear))
        }
      }
    }
  }
}
