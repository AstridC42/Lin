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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatScreen
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.McpManagerBottomSheet
import com.google.ai.edge.gallery.customtasks.agentchat.McpManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.resetSessionWithCurrentSkillsAndMcps
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.memory.MemoryBook
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
  onOpenModels: (() -> Unit)? = null,
  viewModel: AssistantViewModel = hiltViewModel(),
  dictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
  mcpManagerViewModel: McpManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val speaker = rememberAssistantSpeaker()

  val storedSettings = remember { dataStoreRepository.readAssistantSettings() }

  // The engine starts on whatever the phone defaults to, so the saved choice has to be handed to
  // it every time the screen appears - not only when it is changed in the dialog.
  LaunchedEffect(speaker) {
    speaker.applyPreferences(
      voiceId = storedSettings.ttsVoiceName,
      rate = storedSettings.ttsSpeechRate,
      pitch = storedSettings.ttsPitch,
    )
  }

  var mode by remember { mutableStateOf(AssistantMode.fromId(storedSettings.modeId)) }
  var speakReplies by remember { mutableStateOf(storedSettings.speakReplies) }
  var showModeSheet by remember { mutableStateOf(false) }
  var showWebSearchSettings by remember { mutableStateOf(false) }
  var showVoiceSettings by remember { mutableStateOf(false) }
  var showMusicApp by remember { mutableStateOf(false) }
  var showMemory by remember { mutableStateOf(false) }
  var showMcpServers by remember { mutableStateOf(false) }
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

  // Whether there is anything to tell the model about the user. Shares the revision counter with
  // the capability settings because it changes the prompt the same way they do: the section is
  // present or absent, and the session has to be rebuilt either way.
  val hasMemory =
    remember(webSearchRevision) { dataStoreRepository.readAssistantMemory().factCount > 0 }

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
          hasMemory = hasMemory,
        )
      }
    }

  /**
   * Rebuilds the session so a change to the prompt takes effect.
   *
   * Everything that goes into the system prompt - the mode, what is remembered, which MCP tools
   * are selected - is baked in when the session is built, so changing any of it means building a
   * new one. History is kept, because from the user's side this is changing a setting mid
   * conversation rather than starting over.
   *
   * It carries the two substitutions the shared helper does not know about on its own: the skill
   * filter that hides what the assistant's own tools do better, and the memory section. A
   * placeholder nobody substitutes reaches the model as the literal text `___MEMORY___`.
   */
  val rebuildSession: (AssistantMode) -> Unit = { forMode ->
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
        // Read live rather than using the values remembered above. A settings dialog bumps the
        // revision counter and rebuilds in the same lambda, and the remembered values do not
        // recompute until the next composition - so using them here would describe the settings
        // as they were before the user changed them, which is the whole thing being fixed.
        AssistantPrompts.resolve(
          currentPrompt = prompt,
          mode = forMode,
          hasMcpTools = hasMcpTools,
          webSearchEnabled = searxngClient.isConfigured(),
          webSearchInstance =
            searxngClient.instanceUrlOrNull()?.let { AssistantTask.hostOf(it) }.orEmpty(),
          smartHomeEnabled = homeAssistantClient.isConfigured(),
          hasMemory = dataStoreRepository.readAssistantMemory().factCount > 0,
        )
      },
      skillsForPrompt = { skills -> skills.filterNot { it.name in AssistantTask.SUPERSEDED_BY_TOOLS } },
      extraSubstitutions =
        mapOf(
          AssistantPrompts.MEMORY_PLACEHOLDER to
            MemoryBook.format(dataStoreRepository.readAssistantMemory())
        ),
    )
  }

  val applyMode: (AssistantMode) -> Unit = { newMode ->
    if (newMode != mode) {
      mode = newMode
      dataStoreRepository.saveAssistantSettings(
        dataStoreRepository.readAssistantSettings().toBuilder().setModeId(newMode.id).build()
      )
      rebuildSession(newMode)
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
        dataStoreRepository = dataStoreRepository,
        onOpenGallery = onOpenGallery,
        onOpenWebSearchSettings = { showWebSearchSettings = true },
        onOpenWakeWordSettings = { showWakeWordSettings = true },
        onOpenSmartHomeSettings = { showSmartHomeSettings = true },
        onOpenVoiceSettings = { showVoiceSettings = true },
        onOpenMusicApp = { showMusicApp = true },
        onOpenMemory = { showMemory = true },
        onOpenMcpServers = { showMcpServers = true },
        onOpenModels = onOpenModels,
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
      emptyStateHeadline = {
        AssistantEmptyStateHeadline(
          webSearchEnabled = webSearchEnabled,
          smartHomeEnabled = smartHomeEnabled,
          // Sending it straight away rather than filling the text box: these are complete
          // requests, and a tap that only types something needs a second tap to do anything.
          onSuggestion = { dictatedQuery = it },
        )
      },
      aboveInputContent = {
        AssistantModeBar(
          mode = mode,
          webSearchEnabled = webSearchEnabled,
          speakReplies = speakReplies,
          onModeClicked = { showModeSheet = true },
          onWebSearchClicked = { showWebSearchSettings = true },
          onVoiceSettingsClicked = { showVoiceSettings = true },
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
      dataStoreRepository = dataStoreRepository,
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
      onOpenVoiceSettings = {
        showManual = false
        showVoiceSettings = true
      },
      onOpenMusicApp = {
        showManual = false
        showMusicApp = true
      },
      onOpenMemory = {
        showManual = false
        showMemory = true
      },
      onOpenMcpServers = {
        showManual = false
        showMcpServers = true
      },
      onOpenModels =
        onOpenModels?.let {
          {
            showManual = false
            it()
          }
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
        // derived values above recompute from it. And both need the session rebuilt for the tool
        // to reach the model at all.
        webSearchRevision++
        rebuildSession(mode)
      },
    )
  }

  if (showMcpServers) {
    McpManagerBottomSheet(
      mcpManagerViewModel = mcpManagerViewModel,
      onDismiss = { toolsChanged ->
        showMcpServers = false
        // The MCP tools are listed in the system prompt and attached to the session, so a change
        // to which ones are selected means nothing at all until the session is rebuilt.
        if (toolsChanged) rebuildSession(mode)
      },
    )
  }

  if (showMemory) {
    MemoryDialog(
      dataStoreRepository = dataStoreRepository,
      onDismiss = { showMemory = false },
      // Editing what is remembered changes the system prompt, so the session has to be rebuilt
      // for it to take effect - the same path a capability setting takes.
      onChanged = {
        webSearchRevision++
        rebuildSession(mode)
      },
    )
  }

  if (showMusicApp) {
    MusicAppDialog(
      dataStoreRepository = dataStoreRepository,
      onDismiss = { showMusicApp = false },
    )
  }

  if (showVoiceSettings) {
    VoiceSettingsDialog(
      dataStoreRepository = dataStoreRepository,
      speaker = speaker,
      onDismiss = { showVoiceSettings = false },
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
        // Which search tool exists is decided when the session is built: unconfigured, the model
        // is given a stand-in whose only answer is "web search is not set up". Setting the
        // address up changed nothing until the session was rebuilt, so the assistant went on
        // saying it could not search with the instance sitting configured behind it.
        rebuildSession(mode)
      },
    )
  }
}

/**
 * What fills the screen before the first message.
 *
 * The suggestions are the point of it. Everything this assistant can do that an ordinary chat
 * cannot - reading the notification shade, setting a reminder that survives a reboot, loading a
 * skill to look something up - is invisible until it is asked for, and nobody asks for a capability
 * they have no reason to think exists. A screen that only says "what can I do for you?" is a screen
 * that gets asked the questions any chatbot could answer.
 *
 * So they are examples of the kinds of request that go somewhere, not a menu, and they are filtered
 * to what is actually wired up: offering a web search on a phone with no instance configured would
 * teach exactly the wrong lesson on the first try.
 */
@Composable
private fun AssistantEmptyStateHeadline(
  webSearchEnabled: Boolean,
  smartHomeEnabled: Boolean,
  onSuggestion: (String) -> Unit,
) {
  Column(
    modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      painterResource(R.drawable.gemini_star),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(44.dp),
    )
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

    val suggestions = buildList {
      add(stringResource(R.string.assistant_suggestion_weather))
      add(stringResource(R.string.assistant_suggestion_missed))
      add(stringResource(R.string.assistant_suggestion_lookup))
      add(stringResource(R.string.assistant_suggestion_remind))
      if (webSearchEnabled) add(stringResource(R.string.assistant_suggestion_search))
      if (smartHomeEnabled) add(stringResource(R.string.assistant_suggestion_lights))
      add(stringResource(R.string.assistant_suggestion_playing))
    }
    FlowRow(
      modifier = Modifier.padding(top = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      for (suggestion in suggestions) {
        SuggestionChip(onClick = { onSuggestion(suggestion) }, label = { Text(suggestion) })
      }
    }
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
  onVoiceSettingsClicked: () -> Unit,
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
    // Held down, it opens the voice picker - the setting people look for the moment they have
    // heard the default voice once.
    LongPressChip(
      label = stringResource(R.string.assistant_speak),
      icon = if (speakReplies) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VoiceOverOff,
      contentDescription = stringResource(R.string.cd_assistant_speak_replies),
      highlighted = speakReplies,
      onClick = onSpeakRepliesToggled,
      onLongClick = onVoiceSettingsClicked,
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

/**
 * A chip that does one thing tapped and another held down.
 *
 * Material's own [AssistChip] takes a single `onClick` and handles pointer input itself, so a long
 * press placed on it from outside never arrives - the chip has already consumed the gesture. This
 * is the same shape drawn over a [combinedClickable] instead, which is the only way to give a chip
 * a second, hidden action without leaving the chip row's visual language.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressChip(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String?,
  highlighted: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val contentColor =
    if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = Color.Transparent,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      Modifier.height(32.dp)
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        icon,
        contentDescription = contentDescription,
        tint = contentColor,
        modifier = Modifier.size(AssistChipDefaults.IconSize),
      )
      Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
  }
}
