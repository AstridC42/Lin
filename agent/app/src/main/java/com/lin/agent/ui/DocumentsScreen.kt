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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import java.text.DateFormat
import java.util.Date

/**
 * What Lin can read, and what it has written.
 *
 * The list is the access model made visible. There is no browsing anywhere in this app: each row
 * is one file whose owner handed it over, and removing a row releases the permission as well as
 * the row - so this screen is a complete and truthful answer to "what can Lin open".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
  onBack: () -> Unit,
  /** A document shared to Lin from another app, to be taken on when this screen opens. */
  incoming: Uri? = null,
  viewModel: DocumentsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // Keyed on the URI so it is taken on once rather than on every recomposition, and so a second
  // share in the same session is not mistaken for the first.
  LaunchedEffect(incoming) { incoming?.let { viewModel.add(it, context) } }

  // Lin writes documents from a tool, in the background, so the list can change while this
  // screen is open.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.add(it, context) }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.documents_title)) },
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
        stringResource(R.string.documents_explain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedButton(
        onClick = { picker.launch(viewModel.pickerTypes()) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.documents_add))
      }

      if (state.error.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewModel::dismissError) {
              Text(stringResource(R.string.documents_dismiss))
            }
          }
        }
      }

      if (!state.loading && state.documents.isEmpty()) {
        Text(
          stringResource(R.string.documents_empty),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(vertical = 12.dp),
        )
      }

      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.documents, key = { it.uri }) { document ->
          Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(document.name, style = MaterialTheme.typography.bodyLarge)
                  Text(
                    stringResource(
                      if (document.written) R.string.documents_written_at
                      else R.string.documents_added_at,
                      DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(document.addedAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(onClick = { viewModel.forget(document, context) }) {
                  Icon(
                    Icons.Filled.Delete,
                    stringResource(
                      if (document.written) R.string.documents_delete
                      else R.string.documents_forget
                    ),
                  )
                }
              }
              // Only for what Lin wrote. This is the step that takes a document out of Lin's
              // private storage and puts it among the user's own files - and it is theirs to
              // take, which is why no tool can reach it.
              if (document.written) {
                TextButton(
                  onClick = {
                    viewModel.shareIntent(document, context)?.let { intent ->
                      context.startActivity(
                        android.content.Intent.createChooser(
                          intent,
                          context.getString(R.string.documents_send),
                        )
                      )
                    }
                  }
                ) {
                  Text(stringResource(R.string.documents_send))
                }
              }
            }
          }
        }
      }
    }
  }
}
