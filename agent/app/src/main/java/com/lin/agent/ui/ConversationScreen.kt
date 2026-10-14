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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface

/**
 * The conversation, full screen.
 *
 * When no model is on the device this offers the download instead of a text box. There is no
 * stand-in to fall back to: the app answers from a real engine or says it cannot, because an echo
 * that looks like a reply is the worst of the three options.
 *
 * Messages are width-capped rather than stretched. On a Fold 7 unfolded this is an 8-inch screen,
 * and a line of text running its full width is unreadable; the cap is what makes the same screen
 * work folded and open. A proper two-pane layout for the large screen comes with the agent, when
 * there is tool activity worth showing beside the conversation.
 *
 * The chrome is all that is here. Everything below it is [ConversationBody], shared with the
 * panel Lin wears when it is summoned - see [AssistPanel]. Two frames, one conversation screen:
 * the alternative was a second composable that would have drifted from this one by the first
 * time either was changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
  launch: Launch = Launch.NORMAL,
  /** Words already said - what followed "Hey Lin". Asked instead of opening the microphone. */
  spoken: String = "",
  /** Which summon this is, so it is acted on once. See [com.lin.agent.core.voice.Summon]. */
  token: String = "",
  onOpenSettings: () -> Unit = {},
  onGetModel: () -> Unit = {},
  viewModel: ConversationViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

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
      ConversationBody(
        launch = launch,
        spoken = spoken,
        token = token,
        // Fills what the screen has, which is the difference between this frame and the panel.
        fillHeight = true,
        onGetModel = onGetModel,
        viewModel = viewModel,
      )
    }
  }
}

/**
 * Lin as a panel over whatever you were doing, which is what being summoned should look like.
 *
 * The full screen is the wrong shape for a ten-second question: it replaces what somebody was
 * reading, and coming back out of it is a second deliberate act. This sits at the bottom of the
 * screen over a dimmed view of where they already were, so answering a question costs them
 * nothing but the answer.
 *
 * Three decisions worth stating, because each had an obvious wrong version:
 *
 * The panel is as tall as what is in it, up to a cap, rather than a fixed height. A fixed panel
 * shows an expanse of empty surface before the first word and then clips the reply that arrives;
 * growing from the composer upwards means the shape follows the conversation.
 *
 * The cap is a fraction of the window rather than a number of dp, so it is a panel on a phone
 * and still a panel on a Fold's inner screen.
 *
 * What is above the panel dismisses it, and that area is a sibling of the panel rather than a
 * scrim drawn under it. A full-size scrim would rely on the panel swallowing every touch that
 * lands on it, which it is under no obligation to do - and a stray tap on your own conversation
 * closing it is the kind of bug that makes a thing feel broken.
 */
@Composable
fun AssistPanel(
  launch: Launch = Launch.ASSIST,
  spoken: String = "",
  token: String = "",
  onClose: () -> Unit = {},
  /** Opens the full app, for the one thing the panel cannot do: downloading a model. */
  onGetModel: () -> Unit = {},
  viewModel: ConversationViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // The cap is on the panel rather than on the conversation inside it, which is the right way
    // round: the conversation then shrinks to make room for whatever else has to be shown - an
    // attachment, a notice, a reply waiting to be sent - instead of pushing the composer off the
    // bottom of the screen. Three fifths of the window at its tallest: enough to read an answer,
    // little enough to still be over something rather than instead of it.
    val panelMaxHeight = maxHeight * 0.6f

    Column(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .clickable(
              // No ripple and no sound: this is a way out, not a button. A ripple spreading
              // across somebody's home screen would be nonsense.
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = onClose,
            )
      )

      Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight),
      ) {
        // One bottom inset rather than two. The keyboard's inset and the navigation bar's
        // overlap - the keyboard is drawn over where the bar was - so padding for both in turn
        // leaves a bar-sized gap above an open keyboard. safeDrawing takes the larger of them,
        // which is also right when the window is resized for the keyboard instead and the
        // keyboard inset is zero.
        Column(
          modifier =
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
          Row(
            modifier =
              Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Who is answering, for the same reason the full screen says it: a reply from the
            // phone when the server was expected has to be visible, panel or not.
            Text(
              state.backendId.ifEmpty { stringResource(R.string.no_model) },
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onClose) {
              Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_panel))
            }
          }

          ConversationBody(
            launch = launch,
            spoken = spoken,
            token = token,
            // Takes what is left of the panel rather than all of it, so a one-line answer is a
            // one-line panel.
            fillHeight = false,
            onGetModel = onGetModel,
            viewModel = viewModel,
          )
        }
      }
    }
  }
}

/**
 * The conversation itself, and the composer: everything that is the same in both frames.
 *
 * [fillHeight] is the one difference between them, and it is all of it. Full screen, the
 * conversation takes the space that is left so that the composer sits at the bottom of the
 * screen. In the panel it takes only as much as it needs, up to what is left of the panel's
 * cap - so a one-line answer is a one-line panel, and anything else that has to be shown
 * squeezes the conversation rather than pushing the composer off the bottom.
 *
 * Why a ColumnScope extension rather than a Composable taking a Modifier: both frames need
 * `weight`, which only exists in this scope, and handing a pre-built modifier in from outside
 * would put the layout decision in both callers instead of here.
 */
@Composable
private fun ColumnScope.ConversationBody(
  launch: Launch,
  spoken: String,
  token: String,
  fillHeight: Boolean,
  onGetModel: () -> Unit,
  viewModel: ConversationViewModel,
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

  // Once per summon. The deciding is in the view model rather than here, because "once" has to
  // survive this composition being thrown away and rebuilt - which on a foldable happens every
  // time the device is opened, and used to ask a wake-word question a second time.
  LaunchedEffect(token, launch, spoken) {
    viewModel.onSummoned(
      token = token,
      spoken = spoken,
      launch = launch,
      microphoneGranted =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED,
    )
  }
  val listState = rememberLazyListState()

  // Follow the reply as it arrives, and the new message when one is sent.
  val lastIndex = state.messages.size
  LaunchedEffect(lastIndex, state.streaming) {
    if (lastIndex > 0 || state.streaming.isNotEmpty()) {
      listState.animateScrollToItem(maxOf(0, lastIndex))
    }
  }

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
    modifier =
      Modifier.weight(1f, fill = fillHeight)
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
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
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
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
    // Nothing below this is any use without a model, and a text box that cannot be answered is
    // worse than no text box.
    return
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
          trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.attach_remove)) },
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
        Text(stringResource(if (state.listening) R.string.listening else R.string.ask_placeholder))
      },
      readOnly = state.listening,
      maxLines = 5,
    )
    // The microphone, asked for at the point of use. An assistant that requests the
    // microphone at launch, before being asked to listen to anything, is asking on the
    // wrong terms.
    val micPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.speak))
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
      Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
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
