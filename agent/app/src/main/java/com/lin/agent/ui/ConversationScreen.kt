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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.engine.Message

/**
 * The conversation, which is the whole app for now.
 *
 * Phase 0 deliberately ships a working chat against [com.lin.agent.engine.FakeBackend], so the
 * screen, the view model and the streaming path are all proven before a real engine arrives
 * underneath them in Phase 1.
 *
 * Messages are width-capped rather than stretched. On a Fold 7 unfolded this is an 8-inch screen,
 * and a line of text running its full width is unreadable; the cap is what makes the same screen
 * work folded and open. A proper two-pane layout for the large screen comes with the agent, when
 * there is tool activity worth showing beside the conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(viewModel: ConversationViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  var draft by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Follow the reply as it arrives, and the new message when one is sent.
  val lastIndex = state.messages.size
  LaunchedEffect(lastIndex, state.streaming) {
    if (lastIndex > 0 || state.streaming.isNotEmpty()) {
      listState.animateScrollToItem(maxOf(0, lastIndex))
    }
  }

  Scaffold(
    topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    modifier = Modifier.imePadding(),
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        itemsIndexed(state.messages) { _, message -> Bubble(message) }
        if (state.streaming.isNotEmpty()) {
          item { Bubble(Message(Message.Role.ASSISTANT, state.streaming)) }
        }
        if (state.error.isNotEmpty()) {
          item {
            Card(
              colors =
                CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.errorContainer
                ),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
              )
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text(stringResource(R.string.ask_placeholder)) },
          maxLines = 5,
        )
        IconButton(
          onClick = {
            viewModel.send(draft)
            draft = ""
          },
          enabled = draft.isNotBlank() && !state.inProgress,
        ) {
          Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(R.string.send),
          )
        }
      }
    }
  }
}

@Composable
private fun Bubble(message: Message) {
  val fromUser = message.role == Message.Role.USER
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
  ) {
    Card(
      colors =
        CardDefaults.cardColors(
          containerColor =
            if (fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
      // Capped, not stretched: see the note on the screen above.
      modifier = Modifier.widthIn(max = 560.dp),
    ) {
      Text(
        message.text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      )
    }
  }
}
