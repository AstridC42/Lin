/*
 * Copyright 2026 The Star Storyteller Authors
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatScreen
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.McpManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.resetSessionWithCurrentSkillsAndMcps
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.smarthome.HomeAssistantClient
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle

/**
 * The single chat screen that replaces Google Assistant.
 *
 * It is the upstream Agent Chat screen - the richest of Edge Gallery's screens, with skills, MCP
 * servers, the JavaScript skill runtime and the collapsible progress panel - extended in three
 * ways:
 * - a mode bar that biases the system prompt without ever taking a capability away, so one turn
 *   can attach a photo *and* ask for a phone call,
 * - live web search through the user's own SearXNG instance,
 * - spoken replies, for hands-free use.
 *
 * Model switching is already part of the chat app bar; because every general-purpose model in the
 * allowlist is attached to this task (see
 * [com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel.loadModelAllowlist]), any of
 * them can be picked - and downloaded - without leaving the conversation.
 */
@Composable
fun AssistantScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  agentTools: AgentTools,
  dataStoreRepository: DataStoreRepository,
  searxngClient: SearxngClient,
  homeAssistantClient: HomeAssistantClient,
  initialQuery: String? = null,
  initialImageUris: List<String> = listOf(),
  startListening: Boolean = false,
  onOpenGallery: (() -> Unit)? = null,
  viewModel: AssistantViewModel = hiltViewModel(),
  dictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
  mcpManagerViewModel: McpManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val speaker = rememberAssistantSpeaker()

  val storedSettings = remember { dataStoreRepository.readAssistantSettings() }
  var mode by remember { mutableStateOf(AssistantMode.fromId(storedSettings.modeId)) }
  var speakReplies by remember { mutableStateOf(storedSettings.speakReplies) }
  var showModeSheet by remember { mutableStateOf(false) }
  var showWebSearchSettings by remember { mutableStateOf(false) }
  var showWakeWordSettings by remember { mutableStateOf(false) }
  var showSmartHomeSettings by remember { mutableStateOf(false) }

  // Hands-free conversation: the assistant speaks its answer, then reopens the microphone, so a
  // back-and-forth needs no touch at all. Not persisted - leaving the app listening in a loop
  // across launches is not something to restore silently.
  var handsFree by remember { mutableStateOf(false) }
  // Bumped when the loop wants to listen again; keying the listener on it reopens the microphone
  // for each turn rather than only for the first.
  // One counter for every reason to open the microphone: a hands-free turn, or the Talk button.
  // Keying the listener on it is what re-arms listening, which otherwise happens once and never
  // again.
  var listenTurn by remember { mutableIntStateOf(0) }
  var showManual by remember { mutableStateOf(false) }

  // Bumped whenever the web search settings are edited, so the derived values below recompute
  // without having to observe the whole DataStore.
  var webSearchRevision by remember { mutableIntStateOf(0) }
  val webSearchEnabled = remember(webSearchRevision) { searxngClient.isConfigured() }
  val webSearchHost =
    remember(webSearchRevision) {
      searxngClient.instanceUrlOrNull()?.let { AssistantTask.hostOf(it) }.orEmpty()
    }

  // A hands-free summon has to become a question by itself. The text it produces is handed to the
  // chat as its initial query, which the chat already knows how to send once the model is ready -
  // so a summon made before the model finished loading still asks its question rather than losing
  // it.
  var dictatedQuery by rememberSaveable { mutableStateOf<String?>(null) }
  val listening =
    rememberHandsFreeListening(
      enabled = startListening || handsFree || listenTurn > 0,
      dictateViewModel = dictateViewModel,
      // Each hands-free turn is a fresh chance to listen, so the "already heard this summon" guard
      // is re-armed per turn rather than latched for the life of the screen.
      turn = listenTurn,
      alreadyHeard = dictatedQuery != null,
      onHeard = { dictatedQuery = it },
    )

  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()

  // The mode lives in the system prompt, so it is folded in here rather than sent as a message.
  val smartHomeEnabled = remember(webSearchRevision) { homeAssistantClient.isConfigured() }

  val systemPromptResolver =
    remember(mode, webSearchEnabled, webSearchHost, smartHomeEnabled) {
      { currentPrompt: String, hasMcpTools: Boolean ->
        AssistantPrompts.resolve(
          currentPrompt = currentPrompt,
          mode = mode,
          hasMcpTools = hasMcpTools,
          webSearchEnabled = webSearchEnabled,
          webSearchInstance = webSearchHost,
          smartHomeEnabled = smartHomeEnabled,
        )
      }
    }

  val applyMode: (AssistantMode) -> Unit = { newMode ->
    if (newMode != mode) {
      mode = newMode
      dataStoreRepository.saveAssistantSettings(
        dataStoreRepository.readAssistantSettings().toBuilder().setModeId(newMode.id).build()
      )
      // Rebuilding the session is what actually applies the new prompt. History is kept so the
      // switch feels like changing a setting mid-conversation, not starting over.
      resetSessionWithCurrentSkillsAndMcps(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        uiSystemPrompt,
        agentTools,
        initialMessages =
          viewModel.uiState.value.messagesByModel[selectedModel.name]?.toList() ?: listOf(),
        clearHistory = false,
        baseSystemPromptResolver = { prompt, hasMcpTools ->
          AssistantPrompts.resolve(
            currentPrompt = prompt,
            mode = newMode,
            hasMcpTools = hasMcpTools,
            webSearchEnabled = webSearchEnabled,
            webSearchInstance = webSearchHost,
            smartHomeEnabled = smartHomeEnabled,
          )
        },
      )
      viewModel.addMessage(
        model = selectedModel,
        message =
          ChatMessageInfo(
            content =
              context.getString(
                R.string.assistant_mode_switched,
                context.getString(newMode.labelRes),
              )
          ),
      )
    }
  }

  val manualOnScreen = isLargeScreen()

  AssistantTwoPane(
    showManual = manualOnScreen,
    manual = {
      AssistantManualPane(
        onOpenGallery = onOpenGallery,
        onOpenWebSearchSettings = { showWebSearchSettings = true },
        onOpenWakeWordSettings = { showWakeWordSettings = true },
        onOpenSmartHomeSettings = { showSmartHomeSettings = true },
      )
    },
  ) {
    AgentChatScreen(
      task = task,
      modelManagerViewModel = modelManagerViewModel,
      navigateUp = navigateUp,
      agentTools = agentTools,
      viewModel = viewModel,
      skillManagerViewModel = skillManagerViewModel,
      mcpManagerViewModel = mcpManagerViewModel,
      initialQuery = dictatedQuery ?: initialQuery,
      initialImageUris = initialImageUris,
      baseSystemPromptResolver = systemPromptResolver,
      emptyStateHeadline = { AssistantEmptyStateHeadline() },
      aboveInputContent = {
        AssistantModeBar(
          mode = mode,
          webSearchEnabled = webSearchEnabled,
          speakReplies = speakReplies,
          onModeClicked = { showModeSheet = true },
          onWebSearchClicked = { showWebSearchSettings = true },
        onWakeWordClicked = { showWakeWordSettings = true },
        onTalkClicked = { listenTurn++ },
        onManualClicked = { showManual = true },
        showManualButton = !isLargeScreen(),
        handsFree = handsFree,
        onHandsFreeToggled = {
          val enabled = !handsFree
          handsFree = enabled
          if (enabled) {
            listenTurn++
          } else {
            speaker.stop()
            listening.cancel()
          }
        },
          onSpeakRepliesToggled = {
            val enabled = !speakReplies
            speakReplies = enabled
            dataStoreRepository.saveAssistantSettings(
              dataStoreRepository
                .readAssistantSettings()
                .toBuilder()
                .setSpeakReplies(enabled)
                .build()
            )
            if (!enabled) speaker.stop()
          },
          onOpenGallery = onOpenGallery,
        )
      },
      onTurnComplete = { model ->
        val reply =
          viewModel.getLastMessageWithTypeAndSide(
            model = model,
            type = ChatMessageType.TEXT,
            side = ChatSide.AGENT,
          ) as? ChatMessageText
        // The answer has been sent, so the query that produced it is spent. Clearing it here is
        // what lets the next thing heard be treated as a new question rather than as the same one
        // arriving twice.
        dictatedQuery = null
        when {
          handsFree && reply != null ->
            // Listen only once the phone has stopped talking, or it records itself answering.
            speaker.speak(reply.content) { listenTurn++ }
          speakReplies && reply != null -> speaker.speak(reply.content)
          handsFree -> listenTurn++
        }
      },
    )
  }

  if (listening.active) {
    ListeningDialog(heard = listening.partialText, onCancel = listening.cancel)
  }

  if (showModeSheet) {
    AssistantModeSheet(
      selectedMode = mode,
      onModeSelected = {
        applyMode(it)
        showModeSheet = false
      },
      onDismiss = { showModeSheet = false },
    )
  }

  if (showManual) {
    AssistantManualSheet(
      onOpenGallery = onOpenGallery,
      onOpenWebSearchSettings = {
        showManual = false
        showWebSearchSettings = true
      },
      onOpenWakeWordSettings = {
        showManual = false
        showWakeWordSettings = true
      },
      onOpenSmartHomeSettings = {
        showManual = false
        showSmartHomeSettings = true
      },
      onDismiss = { showManual = false },
    )
  }

  if (showSmartHomeSettings) {
    SmartHomeSettingsDialog(
      dataStoreRepository = dataStoreRepository,
      client = homeAssistantClient,
      onDismiss = {
        showSmartHomeSettings = false
        // Shares the revision counter with web search: both change which tools exist, and the
        // derived values above recompute from it.
        webSearchRevision++
      },
    )
  }

  if (showWakeWordSettings) {
    WakeWordSettingsDialog(
      dataStoreRepository = dataStoreRepository,
      onDismiss = { showWakeWordSettings = false },
    )
  }

  if (showWebSearchSettings) {
    WebSearchSettingsDialog(
      dataStoreRepository = dataStoreRepository,
      searxngClient = searxngClient,
      onDismiss = {
        showWebSearchSettings = false
        webSearchRevision++
      },
    )
  }
}

/** The welcome text shown before the first message. */
@Composable
private fun AssistantEmptyStateHeadline() {
  Column(
    modifier = Modifier.padding(horizontal = 40.dp).padding(bottom = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      stringResource(R.string.assistant_emptystate_title),
      style = emptyStateTitle,
      textAlign = TextAlign.Center,
    )
    Text(
      stringResource(R.string.assistant_emptystate_content),
      style = emptyStateContent,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * The always-visible strip above the text field.
 *
 * It shows what the assistant is currently biased toward and gives one-tap access to the things a
 * user changes often: the mode, web search, spoken replies, and the rest of the capability
 * gallery.
 */
@Composable
private fun AssistantModeBar(
  mode: AssistantMode,
  webSearchEnabled: Boolean,
  speakReplies: Boolean,
  onModeClicked: () -> Unit,
  onWebSearchClicked: () -> Unit,
  onWakeWordClicked: () -> Unit,
  onTalkClicked: () -> Unit,
  onManualClicked: () -> Unit,
  showManualButton: Boolean,
  handsFree: Boolean,
  onHandsFreeToggled: () -> Unit,
  onSpeakRepliesToggled: () -> Unit,
  onOpenGallery: (() -> Unit)?,
) {
  Row(
    modifier =
      Modifier.padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AssistChip(
      onClick = onModeClicked,
      label = { Text(stringResource(mode.labelRes)) },
      leadingIcon = {
        Icon(
          mode.icon,
          contentDescription = stringResource(R.string.cd_assistant_mode_button),
          modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
      },
    )

    // The only way to speak to the assistant. The microphone in the "+" menu records an audio
    // clip for the model to listen to, which is a different thing and needs an audio-capable
    // model; there was no dictation anywhere, so the app could not be talked to at all.
    AssistChip(
      onClick = onTalkClicked,
      label = { Text(stringResource(R.string.assistant_talk)) },
      leadingIcon = {
        Icon(
          Icons.Rounded.Mic,
          contentDescription = null,
          modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
      },
    )

    AssistChip(
      onClick = onWebSearchClicked,
      label = { Text(stringResource(R.string.web_search_settings_title)) },
      leadingIcon = {
        Icon(
          Icons.Rounded.TravelExplore,
          contentDescription = null,
          modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
      },
      colors =
        if (webSearchEnabled) {
          AssistChipDefaults.assistChipColors(
            leadingIconContentColor = MaterialTheme.colorScheme.primary
          )
        } else {
          AssistChipDefaults.assistChipColors(
            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
    )

    AssistChip(
      onClick = onWakeWordClicked,
      label = { Text(stringResource(R.string.wake_word_tile_label)) },
      leadingIcon = {
        Icon(
          Icons.Rounded.Mic,
          contentDescription = null,
          modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
      },
    )

    AssistChip(
      onClick = onHandsFreeToggled,
      label = { Text(stringResource(R.string.assistant_hands_free)) },
      leadingIcon = {
        Icon(
          if (handsFree) Icons.Rounded.Headphones else Icons.Rounded.HeadsetOff,
          contentDescription = null,
          modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
      },
      colors =
        if (handsFree) {
          AssistChipDefaults.assistChipColors(
            leadingIconContentColor = MaterialTheme.colorScheme.primary,
            labelColor = MaterialTheme.colorScheme.primary,
          )
        } else {
          AssistChipDefaults.assistChipColors()
        },
    )

    // A labelled chip rather than a bare icon. As an unlabelled speaker glyph this was the only
    // way to hear a reply and nobody found it, so the app read as having no voice output at all.
    AssistChip(
      onClick = onSpeakRepliesToggled,
      label = { Text(stringResource(R.string.assistant_speak)) },
      leadingIcon = {
        Icon(
          if (speakReplies) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VoiceOverOff,
          contentDescription = stringResource(R.string.cd_assistant_speak_replies),
          modifier = Modifier.size(AssistChipDefaults.IconSize),
        )
      },
      colors =
        if (speakReplies) {
          AssistChipDefaults.assistChipColors(
            leadingIconContentColor = MaterialTheme.colorScheme.primary,
            labelColor = MaterialTheme.colorScheme.primary,
          )
        } else {
          AssistChipDefaults.assistChipColors()
        },
    )

    if (showManualButton) {
      AssistChip(
        onClick = onManualClicked,
        label = { Text(stringResource(R.string.assistant_manual)) },
        leadingIcon = {
          Icon(
            Icons.Rounded.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(AssistChipDefaults.IconSize),
          )
        },
      )
    }

    if (onOpenGallery != null) {
      IconButton(onClick = onOpenGallery) {
        Icon(
          Icons.Rounded.Apps,
          contentDescription = stringResource(R.string.cd_assistant_open_gallery),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}
