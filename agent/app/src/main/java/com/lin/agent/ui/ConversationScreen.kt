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

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.core.media.PhotoLibrary
import com.lin.agent.core.voice.Launch
import com.lin.agent.engine.Message

/**
 * The conversation, full screen.
 *
 * The look is the one chosen in October 2026 out of four drawn side by side: cards on a dark
 * ground, with everything Lin did shown as a thing you can act on rather than as grey text.
 * What survived the redesign unchanged is the rule the whole app is built on - every screen
 * says what is actually true. Which model answered is in the header, a tool that only opened
 * something says so on the card, and a message Lin wrote is marked NOT SENT YET until the
 * person sends it.
 *
 * The chrome is all that is here. Everything below it is [ConversationBody], shared with the
 * panel Lin wears when it is summoned - see [AssistPanel].
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
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
          )
          // Which model answered, under the name rather than squeezed beside it. An
          // assistant that quietly answered from somewhere other than the user expects
          // would be worse than one that was simply unavailable.
          Text(
            state.backendId.ifEmpty { stringResource(R.string.no_model) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Only once there is something to end. On an empty conversation it would do nothing
        // visible, which reads as a broken button.
        if (state.messages.isNotEmpty()) {
          RoundIconButton(
            onClick = viewModel::startNewConversation,
            description = stringResource(R.string.new_conversation),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null)
          }
        }
        RoundIconButton(onClick = onOpenSettings, description = stringResource(R.string.open_settings)) {
          Icon(Icons.Filled.Settings, contentDescription = null)
        }
      }
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
 * The cap is on the panel rather than on the conversation inside it, so the conversation
 * shrinks to make room for anything else that has to be shown instead of pushing the composer
 * off the bottom of the screen. The cap is a fraction of the window rather than a number of
 * dp, so it is a panel on a phone and still a panel on a Fold's inner screen. And what is
 * above the panel dismisses it, as a sibling of the panel rather than a scrim drawn under it -
 * a full-size scrim would rely on the panel swallowing every touch that lands on it, which it
 * is under no obligation to do.
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
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight),
      ) {
        // One bottom inset rather than two. The keyboard's inset and the navigation bar's
        // overlap, so padding for both in turn leaves a bar-sized gap above an open keyboard.
        Column(
          modifier =
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
          // The handle says "this is a sheet, it goes away" before anybody reads a word.
          Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(
              modifier =
                Modifier.size(width = 36.dp, height = 4.dp)
                  .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (state.listening) {
              Box(
                modifier =
                  Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape)
              )
              Text(
                stringResource(R.string.listening),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
              )
            } else {
              Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
              state.backendId.ifEmpty { stringResource(R.string.no_model) },
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RoundIconButton(onClick = onClose, description = stringResource(R.string.close_panel), small = true) {
              Icon(Icons.Filled.Close, contentDescription = null)
            }
          }

          ConversationBody(
            launch = launch,
            spoken = spoken,
            token = token,
            // Takes what is left of the panel rather than all of it, so a one-line answer is
            // a one-line panel.
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
 * [fillHeight] is the one difference between them. Full screen, the conversation takes the
 * space that is left so the composer sits at the bottom of the screen. In the panel it takes
 * only as much as it needs, so a one-line answer is a one-line panel and anything else that
 * has to be shown squeezes the conversation rather than pushing the composer off the bottom.
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
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
  }

  LazyColumn(
    state = listState,
    modifier = Modifier.weight(1f, fill = fillHeight).fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // The first thing anybody sees, and until this redesign it was a title bar and a text
    // box. Only when there is genuinely nothing: a half-finished turn is not an empty screen.
    if (state.messages.isEmpty() && state.streaming.isEmpty() && state.missingModel == null) {
      item { NothingYet(onPick = { viewModel.send(it) }) }
    }

    itemsIndexed(state.messages) { _, message -> Said(message) }

    // Before the answer, collapsed, and visibly not the answer. Thinking contradicts itself
    // and abandons approaches on the way to being right, so it is offered rather than shown.
    if (state.thinking.isNotEmpty()) {
      item { Thinking(state.thinking, inProgress = state.inProgress) }
    }
    if (state.streaming.isNotEmpty()) {
      item { Said(Message(Message.Role.ASSISTANT, state.streaming)) }
    }

    // What Lin did, as cards rather than as grey lines: running, done, and could not are
    // three different things and the screen now shows which.
    items(state.toolRuns) { run -> ToolCard(run) }

    // The pictures themselves, not a description of them. A model that cannot see an image
    // has nothing true to say about it, so the screen shows and the model stays quiet.
    if (state.shows.isNotEmpty()) {
      item {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
          items(state.shows) { uri -> Thumbnail(uri) }
        }
      }
    }

    if (state.error.isNotEmpty()) {
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          shape = RoundedCornerShape(22.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            state.error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
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
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      shape = RoundedCornerShape(22.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          StateChip(
            text = stringResource(R.string.reply_not_sent),
            background = MaterialTheme.colorScheme.secondary,
            foreground = MaterialTheme.colorScheme.onSecondary,
          )
          Text(
            stringResource(R.string.reply_to, reply.to, reply.app),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(reply.text, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = { viewModel.sendStagedReply(replyContext) },
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.reply_send))
          }
          TextButton(onClick = viewModel::discardStagedReply, shape = RoundedCornerShape(22.dp)) {
            Text(stringResource(R.string.reply_discard))
          }
        }
      }
    }
  }

  val missing = state.missingModel
  if (missing != null) {
    Card(
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.no_model_title), style = MaterialTheme.typography.titleMedium)
        Text(
          stringResource(
            R.string.no_model_body,
            missing.name,
            "%.1f".format(missing.build.sizeBytes / 1_000_000_000.0),
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGetModel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.no_model_action))
        }
      }
    }
    // Nothing below this is any use without a model, and a text box that cannot be answered
    // is worse than no text box.
    return
  }

  // What is going with the next message, and a way to change your mind before it goes.
  if (state.attached.isNotEmpty()) {
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

  Composer(
    draft = draft,
    onDraft = { draft = it },
    state = state,
    viewModel = viewModel,
    onSent = { draft = "" },
  )
}

/**
 * The empty conversation.
 *
 * Four things Lin can actually do, which is the point: a suggestion that produces "I cannot do
 * that" would be worse than a blank screen. Tapping one sends it as if it had been typed, so
 * nothing here is a special path that could work when the real one does not.
 */
@Composable
private fun NothingYet(onPick: (String) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      stringResource(R.string.empty_greeting),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 4.dp),
    )

    val suggestions =
      listOf(R.string.empty_today, R.string.empty_music, R.string.empty_flashlight, R.string.empty_missed)
    for (pair in suggestions.chunked(2)) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        for (suggestion in pair) {
          val text = stringResource(suggestion)
          Card(
            onClick = { onPick(text) },
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.weight(1f).height(96.dp),
          ) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
              Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomStart),
              )
            }
          }
        }
      }
    }

    Text(
      stringResource(R.string.empty_local),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
  }
}

/** One turn. The person gets a bubble; Lin gets a card, because Lin's answers are longer. */
@Composable
private fun Said(message: Message) {
  val fromUser = message.role == Message.Role.USER
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
  ) {
    if (fromUser) {
      Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 22.dp),
        modifier = Modifier.widthIn(max = 300.dp),
      ) {
        Text(
          message.text,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    } else {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 8.dp),
        // Capped, not stretched: on a Fold's inner screen a line of text running the full
        // width is unreadable, and the same cap works folded and open.
        modifier = Modifier.widthIn(max = 560.dp),
      ) {
        // Markdown, laid out as what it meant rather than shown as what it typed.
        MarkdownText(message.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
      }
    }
  }
}

/**
 * What a tool did, and which of the three things happened.
 *
 * Running, done and could-not are shown differently, and never by colour alone: each carries
 * its own word. The project's rule about staged actions holds here too - a tool that only
 * opened something says so in its own result, and the model is told to repeat that rather
 * than claim the thing was done.
 */
@Composable
private fun ToolCard(run: ToolRun) {
  val colors = MaterialTheme.colorScheme
  val (label, tint) =
    when (run.state) {
      ToolState.RUNNING -> stringResource(R.string.tool_running) to colors.onSurfaceVariant
      ToolState.DONE -> stringResource(R.string.tool_done) to colors.tertiary
      ToolState.FAILED -> stringResource(R.string.tool_failed_short) to colors.error
    }

  Surface(
    color = colors.surfaceContainer,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (run.state == ToolState.RUNNING) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
      }
      Text(
        run.name.replace('_', ' '),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
  }
}

/** A word with a background: the one visual that says a state rather than describing it. */
@Composable
private fun StateChip(text: String, background: Color, foreground: Color) {
  Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(20.dp)) {
    Text(
      text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
    )
  }
}

/** The round icon buttons the header and the panel are built from. */
@Composable
private fun RoundIconButton(
  onClick: () -> Unit,
  description: String,
  small: Boolean = false,
  content: @Composable () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    // The name is on the button rather than on the picture inside it, so a screen reader
    // reads one thing: the icons below are passed contentDescription = null for that reason.
    modifier =
      Modifier.size(if (small) 32.dp else 42.dp).semantics { contentDescription = description },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Box(modifier = Modifier.size(if (small) 16.dp else 20.dp)) { content() }
    }
  }
}

/**
 * The composer: attach, type or speak, send.
 *
 * The microphone is a filled circle rather than another grey glyph, because speaking is how
 * this app is used in a car and from a summon, and the thing somebody reaches for at 70mph
 * should not be the same weight as the paperclip.
 */
@Composable
private fun ColumnScope.Composer(
  draft: String,
  onDraft: (String) -> Unit,
  state: ConversationState,
  viewModel: ConversationViewModel,
  onSent: () -> Unit,
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
  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) viewModel.startListening()
    }
  var choosing by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box {
      RoundIconButton(
        onClick = { if (!state.inProgress) choosing = true },
        description = stringResource(R.string.attach),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null)
      }
      DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
        DropdownMenuItem(
          text = { Text(stringResource(R.string.attach_photo)) },
          onClick = {
            choosing = false
            // The photo picker, not a storage permission: it hands over the one picture
            // that was chosen and needs nothing granted.
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
      onValueChange = onDraft,
      modifier = Modifier.weight(1f),
      placeholder = {
        Text(stringResource(if (state.listening) R.string.listening else R.string.ask_placeholder))
      },
      readOnly = state.listening,
      maxLines = 5,
      shape = RoundedCornerShape(24.dp),
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
          unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
        ),
    )

    // One filled button, whose job changes with what there is to do: stop listening, start
    // listening, or send. Three buttons in a row would make the common one harder to hit.
    val sendable = (draft.isNotBlank() || state.attached.isNotEmpty()) && !state.inProgress
    Surface(
      onClick = {
        when {
          state.listening -> viewModel.stopListening()
          sendable -> {
            viewModel.send(draft)
            onSent()
          }
          else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
      },
      shape = CircleShape,
      color =
        if (state.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
      contentColor =
        if (state.listening) MaterialTheme.colorScheme.onError
        else MaterialTheme.colorScheme.onPrimary,
      modifier = Modifier.size(48.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        when {
          state.listening ->
            Icon(
              Icons.Filled.Close,
              contentDescription = stringResource(R.string.stop_listening),
              modifier = Modifier.size(20.dp),
            )
          sendable ->
            Icon(
              Icons.AutoMirrored.Filled.Send,
              contentDescription = stringResource(R.string.send),
              modifier = Modifier.size(20.dp),
            )
          else ->
            Icon(
              painterResource(R.drawable.ic_mic),
              contentDescription = stringResource(R.string.speak),
              modifier = Modifier.size(20.dp),
            )
        }
      }
    }
  }
}

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

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.height(160.dp).widthIn(min = 120.dp, max = 160.dp),
  ) {
    bitmap?.let {
      Image(
        bitmap = it,
        contentDescription = stringResource(R.string.photo_found),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

/**
 * The model's working-out, offered rather than shown.
 *
 * Open while it is thinking, closed once the answer lands: somebody who wants to know why an
 * answer took thirty seconds can open it, and nobody has to read the working to find the reply.
 */
@Composable
private fun Thinking(text: String, inProgress: Boolean) {
  var open by remember(inProgress) { mutableStateOf(inProgress) }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 8.dp)) {
      Text(
        stringResource(if (open) R.string.thinking_hide else R.string.thinking_show),
        style = MaterialTheme.typography.labelLarge,
      )
    }
    if (open) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(14.dp),
        )
      }
    }
  }
}
