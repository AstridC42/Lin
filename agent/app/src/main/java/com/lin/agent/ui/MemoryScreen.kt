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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemorySource
import java.text.DateFormat
import java.util.Date

/**
 * What Lin has kept about you, and how to take it back.
 *
 * Plain and complete rather than pretty. Every memory is shown in full - no truncation with a
 * tap to expand, because something held about a person should not need a second action to read
 * - with where it came from and when, and a delete on every row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  if (state.confirmingForgetAll) {
    AlertDialog(
      onDismissRequest = viewModel::cancelForgetAll,
      title = { Text(stringResource(R.string.memory_forget_all)) },
      text = { Text(stringResource(R.string.memory_forget_all_detail, state.memories.size)) },
      confirmButton = {
        TextButton(onClick = viewModel::forgetAll) {
          Text(stringResource(R.string.memory_forget_all_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::cancelForgetAll) {
          Text(stringResource(R.string.memory_cancel))
        }
      },
    )
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.memory_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.back),
            )
          }
        },
        actions = {
          if (state.memories.isNotEmpty()) {
            TextButton(onClick = viewModel::askToForgetAll) {
              Text(stringResource(R.string.memory_forget_all))
            }
          }
        },
      )
    }
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Text(
          stringResource(R.string.memory_detail),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 12.dp),
        )
      }

      if (!state.loading && state.memories.isEmpty()) {
        item {
          Text(
            stringResource(R.string.memory_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      items(state.memories, key = { it.id }) { memory ->
        MemoryRow(memory = memory, onForget = { viewModel.forget(memory) })
      }

      item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }
  }
}

@Composable
private fun MemoryRow(memory: Memory, onForget: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(memory.text, style = MaterialTheme.typography.bodyLarge)
        Text(
          stringResource(
            when (memory.source) {
              MemorySource.TOLD -> R.string.memory_source_told
              MemorySource.CONVERSATION -> R.string.memory_source_conversation
              MemorySource.PHOTO -> R.string.memory_source_photo
            },
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(memory.createdAt)),
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onForget) {
        Icon(
          Icons.Filled.Delete,
          contentDescription = stringResource(R.string.memory_forget),
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}
