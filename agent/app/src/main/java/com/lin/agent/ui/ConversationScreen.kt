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

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.core.media.PhotoLibrary
import com.lin.agent.engine.Message

/**
 * The conversation, which is the whole app for now.
 *
 * When no model is on the device this offers the download instead of a text box. There is no
 * stand-in to fall back to: the app answers from a real engine or says it cannot, because an echo
 * that looks like a reply is the worst of the three options.
 *
 * Messages are width-capped rather than stretched. On a Fold 7 unfolded this is an 8-inch screen,
 * and a line of text running its full width is unreadable; the cap is what makes the same screen
 * work folded and open. A proper two-pane layout for the large screen comes with the agent, when
 * there is tool activity worth showing beside the conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
  onOpenSettings: () -> Unit = {},
  onGetModel: () -> Unit = {},
  viewModel: ConversationViewModel = hiltViewModel(),
) {
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
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        // Which backend answered, always visible. There is a real engine and a stand-in, and
        // later a server as well; an assistant that quietly answered from somewhere other than
        // the user expects would be worse than one that was simply unavailable.
        actions = {
          Text(
            state.backendId.ifEmpty { stringResource(R.string.no_model) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          IconButton(onClick = onOpenSettings) {
            Icon(
              Icons.Filled.Settings,
              contentDescription = stringResource(R.string.open_settings),
            )
          }
        },
      )
    },
    modifier = Modifier.imePadding(),
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      // Said once, above the conversation, when what is answering is not what was asked for -
      // the server was chosen and could not be reached. Staying quiet would mean a reply from a
      // model on the phone while the user believed they were talking to the one on the server.
      if (state.notice.isNotEmpty()) {
        Text(
          state.notice,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        itemsIndexed(state.messages) { _, message -> Bubble(message) }
        if (state.streaming.isNotEmpty()) {
          item { Bubble(Message(Message.Role.ASSISTANT, state.streaming)) }
        }
        if (state.runningTool.isNotEmpty()) {
          item {
            Text(
              stringResource(R.string.running_tool),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 4.dp),
            )
          }
        }
        // The pictures themselves, not a description of them. A model that cannot see an image
        // has nothing true to say about it, so the screen shows and the model stays quiet.
        if (state.shows.isNotEmpty()) {
          item {
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
              items(state.shows) { uri -> Thumbnail(uri) }
            }
          }
        }

        // Said plainly and kept on screen, because the model's own account of a failed tool is
        // not evidence: it will often claim the thing happened anyway.
        items(state.failedTools) { failed ->
          Text(
            stringResource(R.string.tool_failed, failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 2.dp),
          )
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

      val missing = state.missingModel
      if (missing != null) {
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              stringResource(R.string.no_model_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              stringResource(
                R.string.no_model_body,
                missing.name,
                "%.1f".format(missing.build.sizeBytes / 1_000_000_000.0),
              ),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGetModel, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.no_model_action))
            }
          }
        }
        return@Column
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

/**
 * One found photo.
 *
 * Loaded through [PhotoLibrary], which already produces a downscaled thumbnail for indexing -
 * so the same system thumbnail serves both, and an image library's worth of transitive
 * dependencies is not added for one row of pictures. `produceState` keyed on the uri means the
 * decode happens off the main thread and is not repeated as the list scrolls.
 */
@Composable
private fun Thumbnail(uri: String) {
  val context = LocalContext.current
  val bitmap: ImageBitmap? by
    produceState<ImageBitmap?>(initialValue = null, uri) {
      val bytes = PhotoLibrary(context).bytesFor(uri)
      value =
        bytes?.let {
          runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
    }

  Card(modifier = Modifier.height(160.dp).widthIn(min = 120.dp, max = 160.dp)) {
    bitmap?.let {
      Image(
        bitmap = it,
        contentDescription = stringResource(R.string.found_photo),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
