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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import android.content.pm.PackageManager
import com.lin.agent.core.media.PhotoLibrary
import com.lin.agent.core.voice.Launch
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
  launch: Launch = Launch.NORMAL,
  /** Words already said - what followed "Hey Lin". Asked instead of opening the microphone. */
  spoken: String = "",
  onOpenSettings: () -> Unit = {},
  onGetModel: () -> Unit = {},
  viewModel: ConversationViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  var draft by rememberSaveable { mutableStateOf("") }
  val context = LocalContext.current

  // "The conversation is over" is what being backgrounded looks like, and onCleared does not
  // run for that - so the digest is triggered from the lifecycle rather than from teardown.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) viewModel.onStopped()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Once per launch, not per recomposition - and not after a fold, which is why it is keyed on
  // the launch rather than on Unit alone.
  LaunchedEffect(launch, spoken) {
    if (spoken.isNotBlank()) {
      // Already said, so asking again would be rude and slower than not having a wake word.
      viewModel.send(spoken, spoken = true)
    } else {
      viewModel.onLaunched(
        launch = launch,
        microphoneGranted =
          context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
      )
    }
  }
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
          // Only once there is something to end. On an empty conversation it would do nothing
          // visible, which reads as a broken button.
          if (state.messages.isNotEmpty()) {
            IconButton(onClick = viewModel::startNewConversation) {
              Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.new_conversation),
              )
            }
          }
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
        // Before the answer, collapsed, and visibly not the answer. Thinking contradicts itself
        // and abandons approaches on the way to being right, so it is offered rather than shown:
        // somebody who wants to know why an answer took thirty seconds can open it, and nobody
        // has to read working-out to find the reply.
        if (state.thinking.isNotEmpty()) {
          item { Thinking(state.thinking, inProgress = state.inProgress) }
        }
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

      // The reply, in full, with the button that sends it. The model wrote these words; the
      // person sends them - and a notification reply has no composer and no undo, so the exact
      // text is shown rather than summarised.
      val staged by viewModel.stagedReply.collectAsStateWithLifecycle()
      staged?.let { reply ->
        val replyContext = LocalContext.current
        Card(
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              stringResource(R.string.reply_to, reply.to, reply.app),
              style = MaterialTheme.typography.labelMedium,
            )
            Text(reply.text, style = MaterialTheme.typography.bodyLarge)
            Text(
              stringResource(R.string.reply_warning),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = { viewModel.sendStagedReply(replyContext) }) {
                Text(stringResource(R.string.reply_send))
              }
              OutlinedButton(onClick = viewModel::discardStagedReply) {
                Text(stringResource(R.string.reply_discard))
              }
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

      // What is going with the next message, and a way to change your mind before it goes.
      if (state.attached.isNotEmpty()) {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
          items(state.attached) { item ->
            AssistChip(
              onClick = { viewModel.detach(item) },
              label = { Text(item.name, maxLines = 1) },
              trailingIcon = {
                Icon(Icons.Filled.Close, stringResource(R.string.attach_remove))
              },
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val attachContext = LocalContext.current
        val photoPicker =
          rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.attach(it, asPicture = true, context = attachContext) }
          }
        val filePicker =
          rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.attach(it, asPicture = false, context = attachContext) }
          }
        var choosing by remember { mutableStateOf(false) }

        Box {
          IconButton(onClick = { choosing = true }, enabled = !state.inProgress) {
            Icon(Icons.Filled.Add, stringResource(R.string.attach))
          }
          DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.attach_photo)) },
              onClick = {
                choosing = false
                // The photo picker, not a storage permission: it hands over the one picture
                // that was chosen and needs nothing granted.
                photoPicker.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.attach_file)) },
              onClick = {
                choosing = false
                filePicker.launch(
                  arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/*",
                  )
                )
              },
            )
          }
        }

        OutlinedTextField(
          // What the microphone has heard so far takes over the field while listening, so an
          // open microphone is never ambiguous and never silent-looking.
          value = if (state.listening) state.heard else draft,
          onValueChange = { draft = it },
          modifier = Modifier.weight(1f),
          placeholder = {
            Text(
              stringResource(
                if (state.listening) R.string.listening else R.string.ask_placeholder
              )
            )
          },
          readOnly = state.listening,
          maxLines = 5,
        )
        // The microphone, asked for at the point of use. An assistant that requests the
        // microphone at launch, before being asked to listen to anything, is asking on the
        // wrong terms.
        val micPermission =
          rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted
            ->
            if (granted) viewModel.startListening()
          }
        if (state.listening) {
          IconButton(onClick = viewModel::stopListening) {
            Icon(
              Icons.Filled.Close,
              contentDescription = stringResource(R.string.stop_listening),
              tint = MaterialTheme.colorScheme.error,
            )
          }
        } else if (draft.isBlank()) {
          IconButton(
            onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            enabled = !state.inProgress,
          ) {
            Icon(
              painterResource(R.drawable.ic_mic),
              contentDescription = stringResource(R.string.speak),
            )
          }
        }
        IconButton(
          onClick = {
            viewModel.send(draft)
            draft = ""
          },
          // Attaching something and saying nothing is a question in itself - see withDocuments.
          enabled = (draft.isNotBlank() || state.attached.isNotEmpty()) && !state.inProgress,
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

/**
 * The model's working-out, folded away.
 *
 * Starts open while the turn is running and closes once the answer is in, which matches what it
 * is for: during the wait it is the only sign anything is happening, and afterwards the answer
 * is what matters. Monospaced and dimmed so it does not read as prose Lin is addressing to
 * anybody.
 */
@Composable
private fun Thinking(text: String, inProgress: Boolean) {
  var open by remember(inProgress) { mutableStateOf(inProgress) }
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp)) {
      Text(
        stringResource(if (open) R.string.thinking_hide else R.string.thinking_show),
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (open) {
      Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
  }
}
