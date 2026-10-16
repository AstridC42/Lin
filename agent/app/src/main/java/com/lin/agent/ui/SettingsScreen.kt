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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import com.lin.agent.core.standing.StandingTask
import com.lin.agent.core.standing.clockTime
import com.lin.agent.core.standing.dayNumbers
import com.lin.agent.core.persona.Answers
import com.lin.agent.core.remind.whenInWords
import com.lin.agent.core.persona.answeredCount
import com.lin.agent.core.persona.personaOrDefault
import com.lin.agent.core.persona.personaPrompt
import com.lin.agent.core.persona.usingDefaultPersona
import com.lin.agent.core.web.SearchProvider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.core.models.DownloadState
import com.lin.agent.core.models.ModelRole
import com.lin.agent.engine.ServerModel
import com.lin.agent.model.IndexState
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.platform.LocalContext
import com.lin.agent.R
import com.lin.agent.core.car.DialPolicy
import com.lin.agent.core.privileged.ShizukuState
import com.lin.agent.core.screen.TapPolicy
import com.lin.agent.screen.accessibilityGranted
import com.lin.agent.screen.openAccessibilitySettings
import com.lin.agent.core.voice.SpeakWhen
import com.lin.agent.core.voice.voiceLabel
import com.lin.agent.engine.Reasoning
import com.lin.agent.engine.ServerKind
import com.lin.agent.AssistActivity
import com.lin.agent.voice.WakeService
import com.lin.agent.voice.assistantSummonsThePanel
import com.lin.agent.voice.canAppearOverOtherApps
import com.lin.agent.voice.linIsTheAssistant
import com.lin.agent.voice.openAppearOverOtherApps
import com.lin.agent.voice.openAssistantSettings
import java.time.ZoneId

/**
 * Settings, in five groups.
 *
 * It was one page until it was fourteen sections long, at which point finding the music app
 * meant scrolling past the model downloads - so the sections are grouped behind tabs, in the
 * order somebody sets Lin up in. Nothing moved between sections and nothing was dropped; the
 * page was cut into five.
 *
 * Each group is a function below rather than a file of its own. They are one screen's worth of
 * layout that shares one state object, and splitting them across files would mean passing that
 * state through five public signatures to no benefit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenMemory: () -> Unit = {},
  onOpenDocuments: () -> Unit = {},
  onOpenTraining: () -> Unit = {},
  onOpenBackup: () -> Unit = {},
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  // Which group is open. Saved, because on a foldable the screen is recreated every time the
  // device is opened and losing your place mid-setup is the whole reason this is not one long
  // page any more. Held as an ordinal rather than the enum, because that is what survives being
  // written into a Bundle without a Saver.
  var openTab by rememberSaveable { mutableIntStateOf(0) }
  val tab = SettingsTab.entries[openTab.coerceIn(SettingsTab.entries.indices)]

  Scaffold(
    topBar = {
      Column {
        TopAppBar(
          title = { Text(stringResource(R.string.settings_title)) },
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
              )
            }
          },
        )
        // Fixed rather than scrollable: five short labels fit across a phone, and tabs that
        // have to be scrolled to be seen hide the thing somebody came here for.
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
          SettingsTab.entries.forEachIndexed { index, each ->
            Tab(
              selected = each == tab,
              onClick = { openTab = index },
              text = { Text(stringResource(each.title)) },
            )
          }
        }
      }
    },
    modifier = Modifier.imePadding(),
  ) { padding ->
    val scroll = rememberScrollState()
    // A new tab starts at its top. Carrying the last tab's scroll position into a shorter page
    // lands somebody in the middle of it, or past the end of it.
    LaunchedEffect(tab) { scroll.scrollTo(0) }

    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(horizontal = 20.dp)
          .verticalScroll(scroll),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Exhaustive on the enum, with no else: adding a group to the list below without giving
      // it anything to show is then a compile error rather than an empty tab.
      when (tab) {
        SettingsTab.MODELS -> ModelsTab(state, viewModel)
        SettingsTab.PERSONA -> PersonaTab(state, viewModel)
        SettingsTab.SERVER -> ServerTab(state, viewModel)
        SettingsTab.VOICE -> VoiceTab(state, viewModel)
        SettingsTab.PHONE -> PhoneTab(state, viewModel)
        SettingsTab.TOOLS -> ToolsTab(state, viewModel)
        SettingsTab.DATA ->
          DataTab(state, viewModel, onOpenMemory, onOpenDocuments, onOpenTraining, onOpenBackup)
      }
      // The last section of any tab should not sit against the navigation bar.
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

/**
 * The groups, in the order they are shown.
 *
 * An enum rather than a list of labels so that the tabs and what each one shows cannot drift
 * apart: the `when` that draws them has no else, so a group added here without content does
 * not compile.
 *
 * The order is roughly "what answers" to "what it keeps", which is also the order somebody
 * sets Lin up in: a model first, because nothing works without one.
 */
private enum class SettingsTab(@param:StringRes val title: Int) {
  MODELS(R.string.settings_tab_models),
  PERSONA(R.string.settings_tab_persona),
  SERVER(R.string.settings_tab_server),
  VOICE(R.string.settings_tab_voice),
  PHONE(R.string.settings_tab_phone),
  TOOLS(R.string.settings_tab_tools),
  DATA(R.string.settings_tab_data),
}

/**
 * What answers, and how hard it thinks.
 *
 * Reasoning sits with the models rather than on its own: it is a property of the model doing
 * the answering, and it is the thing somebody reaches for straight after choosing one.
 */
@Composable
private fun ColumnScope.ModelsTab(state: SettingsState, viewModel: SettingsViewModel) {
  Section(R.string.settings_models_heading) {
    Text(
      stringResource(R.string.settings_device, state.memoryGb, state.socModel.ifEmpty { "?" }),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (line in state.models) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(line.name, style = MaterialTheme.typography.bodyLarge)
          Text(
            line.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          // Only for what is actually here. Offering to use something not downloaded would
          // be a button that cannot work.
          if (line.present && line.model.role == ModelRole.CHAT) {
            if (state.chatModel == line.name) {
              TextButton(onClick = { viewModel.useChatModel(line.name) }) {
                Text(stringResource(R.string.model_chosen))
              }
            } else {
              OutlinedButton(onClick = { viewModel.useChatModel(line.name) }) {
                Text(stringResource(R.string.model_use))
              }
            }
          }
          when (val download = line.download) {
            DownloadState.Waiting ->
              // Indeterminate, because nothing is moving yet. Showing a bar stuck at zero is
              // how a queued download gets mistaken for a broken one.
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    stringResource(R.string.download_waiting),
                    style = MaterialTheme.typography.bodySmall,
                  )
                  TextButton(onClick = { viewModel.cancelDownload(line.model) }) {
                    Text(stringResource(R.string.download_stop))
                  }
                }
              }
            is DownloadState.Running -> {
              // Determinate where the total is known, which it is from the catalogue. A
              // spinner for a twenty-minute transfer tells the user nothing.
              LinearProgressIndicator(
                progress = { download.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  stringResource(
                    R.string.download_progress,
                    download.percent,
                    "%.1f".format(download.receivedBytes / 1_000_000_000.0),
                    "%.1f".format(download.totalBytes / 1_000_000_000.0),
                  ),
                  style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { viewModel.cancelDownload(line.model) }) {
                  Text(stringResource(R.string.download_stop))
                }
              }
            }
            is DownloadState.Failed ->
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  download.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
                // Resuming, not restarting: whatever arrived is still on disk.
                Button(onClick = { viewModel.download(line.model) }) {
                  Text(stringResource(R.string.download_retry))
                }
              }
            else ->
              if (line.present) {
                Text(
                  stringResource(R.string.download_ready),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              } else {
                Button(onClick = { viewModel.download(line.model) }) {
                  Text(stringResource(R.string.download_start))
                }
              }
          }
        }
      }
    }
    // Still shown now that downloading works, because copying a file in is the one way to
    // avoid spending two and a half gigabytes of somebody's allowance twice.
    Text(
      stringResource(R.string.settings_models_where),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      state.modelsDirectory,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(bottom = 32.dp),
    )
  }

  Section(R.string.settings_reasoning_heading) {
    Text(
      stringResource(R.string.settings_reasoning_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
      Column {
        // Radio rows rather than a slider: there are three of them and each one needs a
        // sentence saying what it costs, which a slider has nowhere to put.
        ReasoningChoice(
          level = Reasoning.OFF,
          label = R.string.settings_reasoning_off,
          detail = R.string.settings_reasoning_off_detail,
          selected = state.reasoning,
          onPick = viewModel::setReasoning,
        )
        ReasoningChoice(
          level = Reasoning.BRIEF,
          label = R.string.settings_reasoning_brief,
          detail = R.string.settings_reasoning_brief_detail,
          selected = state.reasoning,
          onPick = viewModel::setReasoning,
        )
        ReasoningChoice(
          level = Reasoning.THOROUGH,
          label = R.string.settings_reasoning_thorough,
          detail = R.string.settings_reasoning_thorough_detail,
          selected = state.reasoning,
          onPick = viewModel::setReasoning,
        )
      }
    }
    // Said plainly, because the levels mean something different depending on which model is
    // answering and a setting that silently means two things is worse than one that explains.
    if (state.answerOnServer) {
      Text(
        stringResource(R.string.settings_reasoning_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

}

/**
 * The machine at the other end: where it is, what it is, and which of its models to use.
 *
 * The address is a single free-text URL rather than a host and a port, because it has to accept
 * both ways this server gets reached: a LAN address at home, and a Cloudflare tunnel hostname
 * from outside. Those differ in scheme, port and host shape.
 *
 * The header field is here from the start rather than added later, and it is worth saying why: a
 * tunnel puts the server on the public internet, and Ollama has no authentication of its own.
 * Without something in front of it, anyone who learns the hostname can use the GPU and read the
 * prompts. This is where a Cloudflare Access service token or a bearer token goes.
 */
@Composable
private fun ColumnScope.ServerTab(state: SettingsState, viewModel: SettingsViewModel) {
  Section(R.string.settings_server_heading) {
    Text(
      stringResource(R.string.settings_server_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Which kind, before the address, because it decides what a correct address looks like
    // and what the key field is for.
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      ServerKind.entries.forEach { kind ->
        FilterChip(
          selected = state.serverKind == kind,
          onClick = { viewModel.setServerKind(kind) },
          label = { Text(serverKindLabel(kind)) },
        )
      }
    }
    Text(
      stringResource(serverKindDetail(state.serverKind)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
      value = state.ollamaUrl,
      onValueChange = viewModel::onUrlChanged,
      label = { Text(stringResource(R.string.settings_server_url)) },
      placeholder = { Text(serverKindPlaceholder(state.serverKind)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = state.ollamaAuthHeader,
      onValueChange = viewModel::onAuthHeaderChanged,
      label = { Text(stringResource(R.string.settings_server_header)) },
      placeholder = { Text("Authorization: Bearer ...") },
      // Not single-line, because a Cloudflare Access service token is two headers and this
      // field exists for exactly that case.
      singleLine = false,
      minLines = 1,
      maxLines = 4,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      stringResource(R.string.settings_server_header_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = viewModel::save) { Text(stringResource(R.string.settings_save)) }
      if (state.saved) {
        Text(
          stringResource(R.string.settings_saved),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(top = 14.dp),
        )
      }
    }
  }

  Section(R.string.settings_server_models_heading) {
    Text(
      stringResource(R.string.settings_server_models_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ServerModels(
      state = state.serverModels,
      picked = state.serverModel,
      typed = state.serverModelTyped,
      onCheck = viewModel::checkServer,
      onPick = viewModel::pickServerModel,
      onType = viewModel::typeServerModel,
    )
  }

  Section(R.string.settings_answer_heading) {
    Text(
      stringResource(R.string.settings_answer_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(vertical = 4.dp)) {
        ChoiceRow(
          title = stringResource(R.string.settings_answer_phone),
          detail = stringResource(R.string.settings_answer_phone_detail),
          selected = !state.answerOnServer,
          onPick = { viewModel.setAnswerOnServer(false) },
        )
        ChoiceRow(
          title = stringResource(R.string.settings_answer_server),
          detail = stringResource(R.string.settings_answer_server_detail),
          selected = state.answerOnServer,
          onPick = { viewModel.setAnswerOnServer(true) },
        )
      }
    }

    Sampling(state, viewModel)
  }

}

/**
 * How the server model should generate.
 *
 * Blank means Lin sends nothing for that field and the server's own configuration decides,
 * which is the default and usually the right answer: somebody who runs a model has already
 * set it up. These are here because the person who owns that server asked to be able to
 * change it from the phone.
 *
 * Only the server. The phone's own model runs under a different engine with different knobs,
 * and one set of boxes pretending to drive both would be a lie on the screen.
 */
@Composable
private fun ColumnScope.Sampling(state: SettingsState, viewModel: SettingsViewModel) {
  Section(R.string.settings_sampling_heading) {
    Text(
      stringResource(R.string.settings_sampling_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Said before the boxes, because it is the one consequence somebody cannot see coming: a
    // model sampling at temperature while emitting an exact tool-call grammar writes calls that
    // do not parse, and the symptom is Lin saying it could not do something it can.
    Text(
      stringResource(R.string.settings_sampling_warning),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    NumberSetting(
      value = state.serverTemperature,
      onChange = viewModel::onTemperatureChanged,
      label = R.string.settings_sampling_temperature,
      hint = R.string.settings_sampling_temperature_hint,
    )
    NumberSetting(
      value = state.serverTopP,
      onChange = viewModel::onTopPChanged,
      label = R.string.settings_sampling_top_p,
      hint = R.string.settings_sampling_top_p_hint,
    )
    NumberSetting(
      value = state.serverTopK,
      onChange = viewModel::onTopKChanged,
      label = R.string.settings_sampling_top_k,
      hint = R.string.settings_sampling_top_k_hint,
    )
    NumberSetting(
      value = state.serverContext,
      onChange = viewModel::onServerContextChanged,
      label = R.string.settings_sampling_context,
      hint = R.string.settings_sampling_context_hint,
    )
    NumberSetting(
      value = state.serverMaxTokens,
      onChange = viewModel::onMaxTokensChanged,
      label = R.string.settings_sampling_max_tokens,
      hint = R.string.settings_sampling_max_tokens_hint,
    )

    // Said here because this is where somebody looks for it. Thinking is one setting that
    // applies to both the phone's model and the server's, so it lives on the Models tab rather
    // than being duplicated into two controls that could disagree.
    Text(
      stringResource(R.string.settings_sampling_thinking),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )

    TextButton(onClick = { viewModel.clearSampling() }) {
      Text(stringResource(R.string.settings_sampling_clear))
    }
  }

}

/**
 * One numeric setting, with what leaving it blank means written underneath.
 *
 * The keyboard is the decimal one rather than a plain number pad: two of these take a
 * fraction, and a pad with no decimal point makes a temperature of 0.8 impossible to type.
 */
@Composable
private fun NumberSetting(
  value: String,
  onChange: (String) -> Unit,
  @StringRes label: Int,
  @StringRes hint: Int,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(stringResource(label)) },
    supportingText = { Text(stringResource(hint)) },
    placeholder = { Text(stringResource(R.string.settings_sampling_unset)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
}

/**
 * Being spoken to and answering out loud, and the two permissions that decide whether either
 * works at all - see linIsTheAssistant and canAppearOverOtherApps.
 */
@Composable
private fun ColumnScope.VoiceTab(state: SettingsState, viewModel: SettingsViewModel) {
  // Two things the phone grants and this screen only reports: whether Lin is the assistant, and
  // whether it may open a window from the background. Both are set in the phone's own settings,
  // so both are stale the moment somebody goes there and comes back - hence read again on every
  // return to the front rather than once.
  val systemContext = LocalContext.current
  var isAssistant by remember { mutableStateOf(linIsTheAssistant(systemContext)) }
  // Not the same question as whether Lin holds the role: a phone told "Lin" before the panel
  // existed points at the whole app by name, and the summons then goes the long way round.
  var summonsThePanel by remember { mutableStateOf(assistantSummonsThePanel(systemContext)) }
  var mayAppearOverOtherApps by remember { mutableStateOf(canAppearOverOtherApps(systemContext)) }
  val systemLifecycle = LocalLifecycleOwner.current
  DisposableEffect(systemLifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        isAssistant = linIsTheAssistant(systemContext)
        summonsThePanel = assistantSummonsThePanel(systemContext)
        mayAppearOverOtherApps = canAppearOverOtherApps(systemContext)
      }
    }
    systemLifecycle.lifecycle.addObserver(observer)
    onDispose { systemLifecycle.lifecycle.removeObserver(observer) }
  }

  Section(R.string.settings_voice_heading) {
    Text(
      stringResource(R.string.settings_voice_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Three ways rather than a switch. "When spoken to" is the default and the one worth
    // having - a phone that answers a typed question out loud in a quiet room is the reason
    // people turn these off - but somebody who wants a voice assistant should be able to say so.
    Text(
      stringResource(R.string.settings_spoken_replies),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(top = 4.dp),
    )
    for (speak in SpeakWhen.entries) {
      ChoiceRow(
        title = stringResource(speakWhenLabel(speak)),
        detail = stringResource(speakWhenDetail(speak)),
        selected = state.speakWhen == speak,
        onPick = { viewModel.setSpeakWhen(speak) },
      )
    }

    // A conversation rather than a series of questions: ask out loud, hear the answer, reply,
    // without reaching for the phone in between. Off by default, because a microphone that
    // reopens by itself is the thing people are right to be wary of.
    Card(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.settings_voice_chat),
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            stringResource(R.string.settings_voice_chat_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = state.voiceChat, onCheckedChange = viewModel::setVoiceChat)
      }
    }

    // The voice itself. Asked for when this group is opened rather than at start-up: the
    // engine takes a moment to come up, and asking it too early gives an empty list that
    // nothing would refresh.
    LaunchedEffect(Unit) { viewModel.loadVoices() }
    val sample = stringResource(R.string.settings_voice_sample)

    Text(
      stringResource(R.string.settings_voice_which),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(top = 12.dp),
    )
    if (state.voices.isEmpty()) {
      Text(
        stringResource(R.string.settings_voice_none),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    for (voice in state.voices) {
      ChoiceRow(
        title = voiceLabel(voice),
        // The engine's own name, which is the only thing telling two identical-looking
        // voices apart - and the thing to quote if one of them misbehaves.
        detail = voice.name,
        selected = state.voiceName == voice.name,
        onPick = { viewModel.useVoice(voice.name, sample) },
      )
    }

    if (state.voices.isNotEmpty()) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            stringResource(R.string.settings_voice_speed, "%.1f".format(state.speechRate)),
            style = MaterialTheme.typography.bodyMedium,
          )
          Slider(
            value = state.speechRate,
            onValueChange = viewModel::setSpeechRate,
            onValueChangeFinished = { viewModel.previewVoice(sample) },
            valueRange = 0.5f..2.0f,
            steps = 14,
          )
          Text(
            stringResource(R.string.settings_voice_pitch, "%.1f".format(state.speechPitch)),
            style = MaterialTheme.typography.bodyMedium,
          )
          Slider(
            value = state.speechPitch,
            onValueChange = viewModel::setSpeechPitch,
            onValueChangeFinished = { viewModel.previewVoice(sample) },
            valueRange = 0.5f..2.0f,
            steps = 14,
          )
        }
      }
    }

    val voiceContext = LocalContext.current
    OutlinedButton(
      // More voices are the system's to install, not Lin's: an engine like Google's or
      // Samsung's downloads them there, and a neural one installed from elsewhere appears in
      // the list above as soon as it is the phone's engine.
      onClick = { openVoiceSettings(voiceContext) },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.settings_voice_more))
    }
    Text(
      stringResource(R.string.settings_voice_more_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val context = LocalContext.current

    // Whether Lin is actually the assistant, read again every time this screen comes back to
    // the front - because the only way to change it is to leave for the system's settings and
    // come back, so a value read once is stale exactly when somebody is looking at it.

    // Said before the button rather than after it, because this is the answer to the question
    // somebody opens this section with: the power button does nothing, is Lin even set? Three
    // states and not two - null is the phone declining to say, and guessing "no" there would
    // be a made-up fact on the one screen meant to clear this up.
    var noAssistantScreen by remember { mutableStateOf(false) }
    Text(
      stringResource(
        when (isAssistant) {
          true -> R.string.settings_assistant_is_lin
          false -> R.string.settings_assistant_is_other
          null -> R.string.settings_assistant_unknown
        }
      ),
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (isAssistant == true) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.error,
    )
    if (isAssistant == true && summonsThePanel == false) {
      Text(
        stringResource(R.string.settings_assistant_old_door),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    OutlinedButton(
      // Android gives no way to request the assistant role directly - it is the user's
      // choice to make in their own settings, which is correct. This only takes them there,
      // to whichever of those screens the phone actually has: starting one it does not
      // throws, and this button used to swallow that and look broken.
      onClick = { noAssistantScreen = !openAssistantSettings(context) },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.settings_set_assistant))
    }
    Text(
      stringResource(
        if (noAssistantScreen) R.string.settings_assistant_no_screen
        else R.string.settings_assistant_detail
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The panel, from inside the app. Half a feature and half a diagnosis: it is the only way
    // to see the thing without a working summon, and whether it appears from here says which
    // half is broken - Lin, or the phone's idea of which app it should be asking.
    OutlinedButton(
      onClick = {
        runCatching {
          context.startActivity(
            Intent(context, AssistActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.settings_try_panel))
    }
    Text(
      stringResource(R.string.settings_try_panel_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  Section(R.string.settings_wake_heading) {
    Text(
      stringResource(R.string.settings_wake_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val wakeContext = LocalContext.current
    val wakePermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setWakeWord(on = true, granted = granted)
        if (granted) WakeService.start(wakeContext)
      }
    Card(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          stringResource(R.string.settings_wake),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.weight(1f),
        )
        Switch(
          checked = state.wakeWord,
          onCheckedChange = { wanted ->
            if (wanted) {
              wakePermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
              viewModel.setWakeWord(on = false, granted = false)
              WakeService.stop(wakeContext)
            }
          },
        )
      }
    }
  }

  Section(R.string.settings_car_heading) {
    Text(
      stringResource(R.string.settings_car_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
      Column {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.settings_car_notification),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              stringResource(R.string.settings_car_notification_detail),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = state.carNotification, onCheckedChange = viewModel::setCarNotification)
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.settings_car_locked),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              stringResource(R.string.settings_car_locked_detail),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = state.answerWhenLocked,
            onCheckedChange = viewModel::setAnswerWhenLocked,
          )
        }
      }
    }

    // The second half of a working wake word, and the half nobody would guess at. Shown only
    // while the wake word is on: it is not a permission to ask for otherwise.
    if (state.wakeWord) {
      Text(
        stringResource(
          if (mayAppearOverOtherApps) R.string.settings_overlay_granted
          else R.string.settings_overlay_missing
        ),
        style = MaterialTheme.typography.bodySmall,
        color =
          if (mayAppearOverOtherApps) MaterialTheme.colorScheme.onSurfaceVariant
          else MaterialTheme.colorScheme.error,
      )
      if (!mayAppearOverOtherApps) {
        OutlinedButton(
          onClick = { openAppearOverOtherApps(systemContext) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.settings_overlay_button))
        }
      }
    }
  }

}

/** What Lin may touch on the phone itself: calls, messages, the calendar, photos, music. */
@Composable
private fun ColumnScope.PhoneTab(state: SettingsState, viewModel: SettingsViewModel) {
  Section(R.string.settings_phone_heading) {
    Text(
      stringResource(R.string.settings_direct_calls),
      style = MaterialTheme.typography.bodyLarge,
    )
    // Three choices rather than a switch, because the right answer is different at a desk and
    // at 70mph: staging the call costs one tap, and that tap is the thing nobody should be
    // doing while driving. The permission is asked for as a dialling choice is made, and a
    // refusal leaves it on "never" - a stored yes the permission does not back would make this
    // lie about what Lin can do.
    val callPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setDialPolicy(viewModel.wantedDialPolicy, granted = granted)
      }
    for (policy in DialPolicy.entries) {
      ChoiceRow(
        title = stringResource(dialPolicyLabel(policy)),
        detail = stringResource(dialPolicyDetail(policy)),
        selected = state.dialPolicy == policy,
        onPick = {
          if (policy == DialPolicy.NEVER) {
            viewModel.setDialPolicy(policy, granted = false)
          } else {
            viewModel.wantedDialPolicy = policy
            callPermission.launch(Manifest.permission.CALL_PHONE)
          }
        },
      )
    }
  }

  Section(R.string.settings_messages_heading) {
    Text(
      stringResource(R.string.settings_messages_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val messagesContext = LocalContext.current
    OutlinedButton(
      onClick = {
        // Notification access cannot be requested - only granted by the user in their own
        // settings, which for this capability is right.
        runCatching {
          messagesContext.startActivity(
            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.settings_notification_access))
    }
  }

  Section(R.string.settings_calendar_heading) {
    Text(
      stringResource(R.string.settings_calendar_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val calendarPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshCalendarAccess(granted)
      }
    if (state.calendarPermitted) {
      Text(
        stringResource(R.string.settings_calendar_granted),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      OutlinedButton(
        onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_calendar_allow))
      }
    }
  }

  Section(R.string.settings_music_heading) {
    Text(
      stringResource(R.string.settings_music_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.musicApps.isEmpty()) {
      Text(
        stringResource(R.string.settings_music_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    for (app in state.musicApps) {
      ChoiceRow(
        title = app.label,
        detail = app.packageName,
        selected = state.musicApp == app.packageName,
        onPick = { viewModel.useMusicApp(app.packageName) },
      )
    }
  }

  /*
   * Reminders, shown here next to the rest of what Lin does with the phone rather than under
   * a tab of its own. This is the list somebody checks when they want to know whether a
   * reminder really exists, which is a question a sentence from a model cannot settle.
   */
  Section(R.string.settings_reminders_heading) {
    Text(
      stringResource(R.string.settings_reminders_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!state.exactAlarms) {
      // Said plainly rather than hidden, because the symptom - a reminder arriving at 18:04 -
      // is otherwise indistinguishable from a bug.
      Text(
        stringResource(R.string.settings_reminders_exact),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = viewModel::askForExactAlarms) {
        Text(stringResource(R.string.settings_reminders_exact_fix))
      }
    }

    if (state.reminders.isEmpty()) {
      Text(
        stringResource(R.string.settings_reminders_none),
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    val now = remember(state.reminders) { System.currentTimeMillis() }
    for (reminder in state.reminders) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(reminder.text, style = MaterialTheme.typography.bodyLarge)
          Text(
            whenInWords(reminder.atMillis, now, ZoneId.systemDefault()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(onClick = { viewModel.cancelReminder(reminder.id) }) {
          Text(stringResource(R.string.settings_reminders_cancel))
        }
      }
    }
  }

  Section(R.string.settings_weather_heading) {
    Text(
      stringResource(R.string.settings_weather_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val weatherContext = LocalContext.current
    var weatherAllowed by remember {
      mutableStateOf(
        weatherContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
      )
    }
    val weatherPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        weatherAllowed = granted
      }
    if (weatherAllowed) {
      Text(
        stringResource(R.string.settings_weather_on),
        style = MaterialTheme.typography.bodyMedium,
      )
    } else {
      OutlinedButton(
        onClick = { weatherPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_weather_allow))
      }
    }
  }

  Section(R.string.settings_photos_heading) {
    Text(
      stringResource(R.string.settings_photos_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val photoPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.indexPhotos(granted)
      }
    when (val indexing = state.photoIndex) {
      is IndexState.Running ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (indexing.total > 0) {
            LinearProgressIndicator(
              progress = { indexing.done.toFloat() / indexing.total },
              modifier = Modifier.fillMaxWidth(),
            )
          } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              if (indexing.total > 0) {
                stringResource(
                  R.string.settings_photos_progress,
                  indexing.done,
                  indexing.total,
                )
              } else stringResource(R.string.settings_photos_preparing),
              style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = viewModel::stopIndexing) {
              Text(stringResource(R.string.settings_photos_stop))
            }
          }
        }
      is IndexState.Failed ->
        Text(
          indexing.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      else -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(
        onClick = { photoPermission.launch(photoPermissionName()) },
        enabled = state.photoIndex !is IndexState.Running,
      ) {
        Text(
          stringResource(
            if (state.photosIndexed > 0) R.string.settings_photos_continue
            else R.string.settings_photos_start
          )
        )
      }
      if (state.photosIndexed > 0) {
        TextButton(onClick = viewModel::clearPhotoIndex) {
          Text(stringResource(R.string.settings_photos_clear))
        }
      }
    }
    Text(
      if (state.photosIndexed > 0) {
        stringResource(R.string.settings_photos_indexed, state.photosIndexed)
      } else stringResource(R.string.settings_photos_none),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ScreenAccess(state, viewModel)
    BorrowedPrivileges(viewModel)
  }

}

/**
 * Lin's screen access, and the one setting that decides whether it may press send.
 *
 * Read from the phone's own list on every return to the front rather than held: it is granted
 * in system settings, outside this app, so anything remembered here is stale the moment
 * somebody goes and grants it.
 */
@Composable
private fun ColumnScope.ScreenAccess(state: SettingsState, viewModel: SettingsViewModel) {
  val context = LocalContext.current
  var granted by remember { mutableStateOf(accessibilityGranted(context)) }
  val lifecycle = LocalLifecycleOwner.current
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) granted = accessibilityGranted(context)
    }
    lifecycle.lifecycle.addObserver(observer)
    onDispose { lifecycle.lifecycle.removeObserver(observer) }
  }

  Section(R.string.settings_screen_heading) {
    Text(
      stringResource(R.string.settings_screen_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(if (granted) R.string.settings_screen_on else R.string.settings_screen_off),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(top = 8.dp),
    )
    // Said before the button rather than after it. What this grants is real, and somebody should
    // read what it is while deciding rather than while undoing it.
    Text(
      stringResource(R.string.settings_screen_warning),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { openAccessibilitySettings(context) }) {
      Text(stringResource(R.string.settings_screen_grant))
    }

    Text(
      stringResource(R.string.settings_tap_send),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(top = 12.dp),
    )
    for (policy in TapPolicy.entries) {
      ChoiceRow(
        title = stringResource(tapPolicyLabel(policy)),
        detail = "",
        selected = state.tapSend == policy,
        onPick = { viewModel.setTapSend(policy) },
      )
    }
    Text(
      stringResource(R.string.settings_tap_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

}

private fun tapPolicyLabel(policy: TapPolicy) =
  when (policy) {
    TapPolicy.NEVER -> R.string.settings_tap_never
    TapPolicy.WHEN_ASKED -> R.string.settings_tap_asked
    TapPolicy.ALWAYS -> R.string.settings_tap_always
  }

/**
 * Shizuku: the shell's privileges, borrowed, so the radios are really set.
 *
 * Five states and a different button for each, because from the outside they all look the same
 * - a switch that did not move - and "install it", "start it", "allow Lin" and "allow Lin from
 * inside Shizuku" are four different things to do. See
 * [com.lin.agent.core.privileged.shizukuState].
 */
@Composable
private fun ColumnScope.BorrowedPrivileges(viewModel: SettingsViewModel) {
  val lifecycle = LocalLifecycleOwner.current
  var state by remember { mutableStateOf(viewModel.shizukuState()) }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      // Read again on every return, because the two likeliest ways this changes - Shizuku
      // being started, and Lin being allowed in Shizuku's own window - both happen in
      // another app while this screen is in the background.
      if (event == Lifecycle.Event.ON_RESUME) state = viewModel.shizukuState()
    }
    lifecycle.lifecycle.addObserver(observer)
    onDispose { lifecycle.lifecycle.removeObserver(observer) }
  }

  Section(R.string.settings_shell_heading) {
    Text(
      stringResource(R.string.settings_shell_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(shellStateLabel(state)),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.padding(top = 8.dp),
    )
    when (state) {
      ShizukuState.READY -> Unit
      ShizukuState.NOT_INSTALLED ->
        OutlinedButton(onClick = { viewModel.openWhereToGetShizuku() }) {
          Text(stringResource(R.string.settings_shell_get))
        }
      ShizukuState.NOT_RUNNING -> {
        OutlinedButton(onClick = { viewModel.openShizuku() }) {
          Text(stringResource(R.string.settings_shell_open))
        }
        Text(
          stringResource(R.string.settings_shell_restart),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      ShizukuState.NOT_PERMITTED ->
        OutlinedButton(
          onClick = {
            viewModel.requestShizuku()
            // Not read back here: the answer arrives in Shizuku's own dialog, after this
            // returns, and the ON_RESUME above is what notices it.
          }
        ) {
          Text(stringResource(R.string.settings_shell_ask))
        }
      ShizukuState.REFUSED ->
        OutlinedButton(onClick = { viewModel.openShizuku() }) {
          Text(stringResource(R.string.settings_shell_open))
        }
    }
  }

}

private fun shellStateLabel(state: ShizukuState) =
  when (state) {
    ShizukuState.READY -> R.string.settings_shell_ready
    ShizukuState.NOT_INSTALLED -> R.string.settings_shell_missing
    ShizukuState.NOT_RUNNING -> R.string.settings_shell_stopped
    ShizukuState.NOT_PERMITTED -> R.string.settings_shell_unasked
    ShizukuState.REFUSED -> R.string.settings_shell_refused
  }

/**
 * Every tool Lin has, with a switch for each.
 *
 * Worth having for two reasons that pull in the same direction. A tool the model should not
 * reach for is one it will reach for eventually - a 2B model offered twenty tools picks the
 * wrong one often enough to be worth a switch - and every tool in the list costs tokens in
 * every prompt, on a phone, before the first word of the answer.
 *
 * Switched off rather than removed: the tool is still listed, still described, and can be
 * switched back on. What changes is only that the model is not told it exists.
 */
@Composable
private fun ColumnScope.ToolsTab(state: SettingsState, viewModel: SettingsViewModel) {
  WebSearch(state, viewModel)

  Section(R.string.settings_tools_heading) {
    Text(
      stringResource(R.string.settings_tools_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(
        R.string.settings_tools_count,
        state.toolListing.size - state.disabledTools.size,
        state.toolListing.size,
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )

    for (tool in state.toolListing) {
      val on = tool.name !in state.disabledTools
      val from = tool.fromServer
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clickable { viewModel.setToolEnabled(tool.name, !on) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(tool.name, style = MaterialTheme.typography.bodyLarge)
          Text(
            // Said on the row rather than left to the prefix in the name: a tool that goes over
            // the network to somebody else's server is a different thing from one that runs on
            // the phone, and the person switching it should be able to see which it is.
            if (from.isEmpty()) tool.description
            else stringResource(R.string.settings_tools_remote, from, tool.description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = on, onCheckedChange = { viewModel.setToolEnabled(tool.name, it) })
      }
    }

    McpServers(viewModel)
  }

}

/**
 * Where Lin searches.
 *
 * Nothing by default, and the search tool says so plainly rather than guessing: a search
 * sends what somebody asked to a third party, and which third party is a choice they make
 * once rather than one this app makes for them.
 *
 * Two providers, neither of them scraped. Fetching a search engine's HTML and pulling the
 * results out was tried first and both of the obvious endpoints answered a plain request
 * with a block page - a feature that works until it quietly does not, for reasons nobody
 * holding the phone could diagnose.
 */
@Composable
private fun ColumnScope.WebSearch(state: SettingsState, viewModel: SettingsViewModel) {
  Section(R.string.settings_search_heading) {
    Text(
      stringResource(R.string.settings_search_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    for (provider in SearchProvider.entries) {
      ChoiceRow(
        title = stringResource(searchProviderLabel(provider)),
        detail = stringResource(searchProviderDetail(provider)),
        selected = state.searchProvider == provider,
        onPick = { viewModel.setSearchProvider(provider) },
      )
    }

    when (state.searchProvider) {
      SearchProvider.NONE -> Unit
      SearchProvider.SEARXNG -> {
        OutlinedTextField(
          value = state.searchUrl,
          onValueChange = viewModel::onSearchUrlChanged,
          label = { Text(stringResource(R.string.settings_search_url)) },
          placeholder = { Text("https://searx.example.com") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        // The misconfiguration everybody hits, said before they hit it: SearXNG serves JSON
        // only when it is switched on, and otherwise answers 403 or an HTML page.
        Text(
          stringResource(R.string.settings_search_searxng_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      SearchProvider.BRAVE ->
        OutlinedTextField(
          value = state.searchKey,
          onValueChange = viewModel::onSearchKeyChanged,
          label = { Text(stringResource(R.string.settings_search_key)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
    }

    // One real search, and what it actually got. Four different faults all read as "I
    // couldn't find anything" in the conversation; a status and a count tell them apart.
    TextButton(onClick = viewModel::trySearch, enabled = !state.searchTrying) {
      Text(
        stringResource(
          if (state.searchTrying) R.string.settings_search_trying else R.string.settings_search_try
        )
      )
    }
    if (state.searchTrial.isNotEmpty()) {
      Text(
        state.searchTrial,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

}

private fun searchProviderLabel(provider: SearchProvider) =
  when (provider) {
    SearchProvider.NONE -> R.string.settings_search_none
    SearchProvider.SEARXNG -> R.string.settings_search_searxng
    SearchProvider.BRAVE -> R.string.settings_search_brave
  }

private fun searchProviderDetail(provider: SearchProvider) =
  when (provider) {
    SearchProvider.NONE -> R.string.settings_search_none_detail
    SearchProvider.SEARXNG -> R.string.settings_search_searxng_detail
    SearchProvider.BRAVE -> R.string.settings_search_brave_detail
  }

/**
 * The MCP servers the user has added, and what each is doing.
 *
 * A server is a URL the model's tool calls are sent to, so the screen says that plainly. The
 * tools it provides appear in the list above with the server's name on them, and can be
 * switched off one at a time like any other.
 *
 * Status is shown per server rather than as one line, because the useful case is the mixed
 * one: a remote server working while a laptop at home is asleep. One being unreachable does
 * not cost the others their tools.
 */
@Composable
private fun ColumnScope.McpServers(viewModel: SettingsViewModel) {
  val servers by viewModel.mcpStatus.collectAsStateWithLifecycle()
  var adding by rememberSaveable { mutableStateOf(false) }
  var name by rememberSaveable { mutableStateOf("") }
  var url by rememberSaveable { mutableStateOf("") }
  var header by rememberSaveable { mutableStateOf("") }

  Section(R.string.settings_mcp_heading) {
    Text(
      stringResource(R.string.settings_mcp_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(R.string.settings_mcp_warning),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (servers.isEmpty()) {
      Text(
        stringResource(R.string.settings_mcp_none),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
      )
    }

    for (each in servers) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(each.server.name, style = MaterialTheme.typography.bodyLarge)
          Text(
            each.server.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            when {
              each.checking -> stringResource(R.string.settings_mcp_checking)
              each.trouble.isNotEmpty() -> each.trouble
              else -> stringResource(R.string.settings_mcp_tools, each.toolCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color =
              if (each.trouble.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.error,
          )
          TextButton(onClick = { viewModel.removeMcpServer(each.server.url) }) {
            Text(stringResource(R.string.settings_mcp_remove))
          }
        }
      }
    }

    if (adding) {
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.settings_mcp_url)) },
        placeholder = { Text("https://example.com/mcp") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.settings_mcp_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = header,
        onValueChange = { header = it },
        label = { Text(stringResource(R.string.settings_mcp_header)) },
        placeholder = { Text("Authorization: Bearer …") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            viewModel.addMcpServer(name, url, header)
            name = ""
            url = ""
            header = ""
            adding = false
          },
          enabled = url.isNotBlank(),
        ) {
          Text(stringResource(R.string.settings_mcp_save))
        }
        TextButton(onClick = { adding = false }) { Text(stringResource(R.string.settings_mcp_cancel)) }
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { adding = true }) {
          Text(stringResource(R.string.settings_mcp_add))
        }
        if (servers.isNotEmpty()) {
          TextButton(onClick = { viewModel.refreshMcp() }) {
            Text(stringResource(R.string.settings_mcp_refresh))
          }
        }
      }
    }
  }

}

/** What Lin keeps: memories, documents, the training copies, and a way out of all of it. */
@Composable
private fun ColumnScope.DataTab(
  state: SettingsState,
  viewModel: SettingsViewModel,
  onOpenMemory: () -> Unit,
  onOpenDocuments: () -> Unit,
  onOpenTraining: () -> Unit,
  onOpenBackup: () -> Unit,
) {
  Section(R.string.settings_memory_heading) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.settings_digest),
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            stringResource(R.string.settings_digest_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = state.digestConversations,
          onCheckedChange = viewModel::setDigestConversations,
        )
      }
    }

    OutlinedButton(onClick = onOpenDocuments, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.documents_title))
    }

    OutlinedButton(onClick = onOpenTraining, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.training_title))
    }

    OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.backup_title))
    }

    OutlinedButton(
      onClick = onOpenMemory,
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
      Text(stringResource(R.string.settings_open_memory))
    }
  }

}

/**
 * The server's model list, and the choice of one.
 *
 * Every state says what to do next rather than only what happened, because this screen is where
 * somebody finds out their phone cannot reach their server and there is no logcat on a phone. The
 * messages come from the client, which is where the status code and the exception are, and are
 * written as sentences for that reason.
 */
@Composable
private fun ServerModels(
  state: ServerModelsState,
  picked: String,
  typed: Boolean,
  onCheck: () -> Unit,
  onPick: (String) -> Unit,
  onType: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(onClick = onCheck, enabled = state !is ServerModelsState.Checking) {
        Text(
          stringResource(
            if (state is ServerModelsState.Unknown) R.string.settings_server_check
            else R.string.settings_server_recheck
          )
        )
      }
      if (state is ServerModelsState.Found && state.models.isNotEmpty()) {
        Text(
          stringResource(R.string.settings_server_found, state.models.size),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    when (state) {
      ServerModelsState.Unknown ->
        Text(
          stringResource(R.string.settings_server_unknown),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      ServerModelsState.Checking ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Text(
            stringResource(R.string.settings_server_checking),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      is ServerModelsState.Failed -> {
        Text(
          state.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        // Not every server lists its models, and that is a configuration rather than a fault -
        // Unsloth's own documentation says to type the id in when a server does not expose
        // /models. Without this the whole feature is a dead end behind a list that was only
        // ever a convenience.
        TypedModel(picked = picked, typed = typed, onType = onType)
      }
      is ServerModelsState.Found ->
        if (state.models.isEmpty()) {
          Text(
            stringResource(R.string.settings_server_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          // A name that is no longer on the server is said out loud rather than silently
          // unselected: the setting is still there, and the user is the one who decides what
          // replaces it.
          if (picked.isNotEmpty() && state.models.none { it.name == picked }) {
            Text(
              stringResource(R.string.settings_server_missing_pick, picked),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              for (model in state.models) {
                ServerModelRow(
                  model = model,
                  selected = model.name == picked,
                  onPick = { onPick(model.name) },
                )
              }
            }
          }
        }
    }
  }
}

@Composable
private fun ServerModelRow(model: ServerModel, selected: Boolean, onPick: () -> Unit) =
  ChoiceRow(title = model.name, detail = describe(model), selected = selected, onPick = onPick)

/**
 * One group of settings, in a card with its name above it.
 *
 * The One UI shape, and it is worth saying why it is worth the trouble. A settings tab is a
 * list of unrelated decisions, and as one flat column the only thing separating "which model
 * answers" from "what Lin may do to the phone" is a slightly larger line of text. Grouping
 * them into cards makes the boundary a thing you can see without reading, which is what lets
 * somebody find a setting by scrolling rather than by reading every line.
 *
 * The heading sits outside the card rather than inside it, which is the detail that makes
 * this read as One UI rather than as a list of boxes: the label belongs to the group, and a
 * label inside the box looks like the first row of it.
 */
@Composable
private fun ColumnScope.Section(
  @StringRes title: Int,
  content: @Composable ColumnScope.() -> Unit,
) {
  Text(
    stringResource(title),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
  )
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}

/**
 * The questions that make Lin somebody's assistant rather than anybody's.
 *
 * A free-text box labelled "system prompt" would be cheaper to build and worse in every other
 * way: most people would leave it empty, and the rest would write something that fights the
 * half of the prompt describing what Lin can actually do. A short questionnaire asks only
 * what the app genuinely cannot work out, and composes the rest.
 *
 * What is shown at the bottom is the real text, not a summary of it. Somebody handing an
 * assistant standing instructions about themselves should be able to read exactly what it
 * was told - and a preview that paraphrased would be the one part of this screen that could
 * quietly lie.
 */
@Composable
private fun ColumnScope.PersonaTab(state: SettingsState, viewModel: SettingsViewModel) {
  Section(R.string.settings_persona_heading) {
    Text(
      stringResource(R.string.settings_persona_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(
        R.string.settings_persona_progress,
        answeredCount(state.persona),
        Answers.entries.size,
      ),
      style = MaterialTheme.typography.bodyMedium,
    )
  }

  for (question in Answers.entries) {
    Section(questionLabel(question)) {
      questionHint(question)?.let {
        Text(
          stringResource(it),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      val answered = state.persona[question.id].orEmpty()
      when (question.kind) {
        Answers.Kind.TEXT ->
          OutlinedTextField(
            value = answered.firstOrNull().orEmpty(),
            onValueChange = { viewModel.setPersonaAnswer(question.id, listOf(it)) },
            singleLine = question != Answers.ABOUT,
            modifier = Modifier.fillMaxWidth(),
          )
        Answers.Kind.ONE_OF ->
          for (option in question.options) {
            ChoiceRow(
              title = stringResource(optionLabel(question, option)),
              detail = "",
              selected = option in answered,
              // Picking the chosen one again clears it, because every question here is
              // optional and a radio group with no way back would make that untrue.
              onPick = {
                viewModel.setPersonaAnswer(
                  question.id,
                  if (option in answered) emptyList() else listOf(option),
                )
              },
            )
          }
        Answers.Kind.ANY_OF ->
          for (option in question.options) {
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clickable {
                    viewModel.setPersonaAnswer(
                      question.id,
                      if (option in answered) answered - option else answered + option,
                    )
                  }
                  .padding(vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Checkbox(
                checked = option in answered,
                onCheckedChange = { on ->
                  viewModel.setPersonaAnswer(
                    question.id,
                    if (on) answered + option else answered - option,
                  )
                },
              )
              Text(
                stringResource(optionLabel(question, option)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
              )
            }
          }
      }
    }
  }

  Routines(state, viewModel)

  Section(R.string.settings_persona_preview_heading) {
    // Deliberately the prompt Lin will really be given, not the answers on this screen. Until
    // somebody answers a question of their own that is the shipped set, and a preview that went
    // blank while Lin carried on being personal would be a screen lying about the app.
    val shipped = usingDefaultPersona(state.persona)
    val composed = personaPrompt(personaOrDefault(state.persona))
    if (shipped) {
      Text(
        stringResource(R.string.settings_persona_preview_shipped),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      composed.ifBlank { stringResource(R.string.settings_persona_preview_empty) },
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (composed.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
    )
    Text(
      stringResource(R.string.settings_persona_preview_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!shipped) {
      TextButton(onClick = { viewModel.clearPersona() }) {
        Text(stringResource(R.string.settings_persona_clear))
      }
    }
  }
}

/**
 * Things Lin does on a schedule without being asked.
 *
 * The feature that makes "assistant" mean something other than "chat window": a question
 * somebody asks every morning is a question they should have to ask once.
 *
 * Shown here rather than under Tools because it is not a capability - it is a standing
 * instruction about what Lin should do with the capabilities it has, which is the same kind
 * of thing as the rest of this tab.
 */
@Composable
private fun ColumnScope.Routines(state: SettingsState, viewModel: SettingsViewModel) {
  var adding by rememberSaveable { mutableStateOf(false) }
  var ask by rememberSaveable { mutableStateOf("") }
  var hour by rememberSaveable { mutableStateOf("8") }
  var minute by rememberSaveable { mutableStateOf("00") }
  var days by rememberSaveable { mutableStateOf(setOf<Int>()) }

  Section(R.string.settings_standing_heading) {
    Text(
      stringResource(R.string.settings_standing_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.standing.isEmpty() && !adding) {
      Text(stringResource(R.string.settings_standing_none), style = MaterialTheme.typography.bodyMedium)
    }

    for (task in state.standing) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(task.ask, style = MaterialTheme.typography.bodyLarge)
          Text(
            clockTime(task) + " · " + whichDays(task),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = task.enabled,
          onCheckedChange = { viewModel.setStandingEnabled(task.id, it) },
        )
        TextButton(onClick = { viewModel.removeStanding(task.id) }) {
          Text(stringResource(R.string.settings_standing_remove))
        }
      }
    }

    if (adding) {
      OutlinedTextField(
        value = ask,
        onValueChange = { ask = it },
        label = { Text(stringResource(R.string.settings_standing_ask)) },
        placeholder = { Text(stringResource(R.string.empty_today)) },
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = hour,
          onValueChange = { hour = it.filter(Char::isDigit).take(2) },
          label = { Text(stringResource(R.string.settings_standing_hour)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = minute,
          onValueChange = { minute = it.filter(Char::isDigit).take(2) },
          label = { Text(stringResource(R.string.settings_standing_minute)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
        )
      }
      Text(
        stringResource(R.string.settings_standing_days),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (day in 1..7) {
          FilterChip(
            selected = day in days,
            onClick = { days = if (day in days) days - day else days + day },
            label = { Text(dayInitial(day)) },
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          enabled = ask.isNotBlank(),
          onClick = {
            viewModel.saveStanding(
              StandingTask(
                id = java.util.UUID.randomUUID().toString(),
                ask = ask.trim(),
                hour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 8,
                minute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                days = days,
              )
            )
            ask = ""
            days = emptySet()
            adding = false
          },
        ) {
          Text(stringResource(R.string.settings_standing_save))
        }
        TextButton(onClick = { adding = false }) {
          Text(stringResource(R.string.settings_standing_cancel))
        }
      }
    } else {
      OutlinedButton(onClick = { adding = true }) {
        Text(stringResource(R.string.settings_standing_add))
      }
    }
  }
}

/** Monday first, matching the day numbers the tasks use. */
@Composable
private fun whichDays(task: StandingTask): String {
  val days = dayNumbers(task)
  return if (days.isEmpty()) stringResource(R.string.settings_standing_everyday)
  else days.joinToString(" ") { dayInitial(it) }
}

private fun dayInitial(day: Int): String =
  java.time.DayOfWeek.of(day.coerceIn(1, 7))
    .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())

private fun questionLabel(question: Answers) =
  when (question) {
    Answers.NAME -> R.string.persona_q_name
    Answers.TONE -> R.string.persona_q_tone
    Answers.LENGTH -> R.string.persona_q_length
    Answers.LANGUAGE -> R.string.persona_q_language
    Answers.PLACE -> R.string.persona_q_place
    Answers.UNITS -> R.string.persona_q_units
    Answers.USES -> R.string.persona_q_uses
    Answers.ASK_FIRST -> R.string.persona_q_ask_first
    Answers.UNSURE -> R.string.persona_q_unsure
    Answers.BIG -> R.string.persona_q_big
    Answers.ABOUT -> R.string.persona_q_about
  }

private fun questionHint(question: Answers): Int? =
  when (question) {
    Answers.NAME -> R.string.persona_q_name_hint
    Answers.LANGUAGE -> R.string.persona_q_language_hint
    Answers.PLACE -> R.string.persona_q_place_hint
    Answers.ASK_FIRST -> R.string.persona_q_ask_first_hint
    Answers.ABOUT -> R.string.persona_q_about_hint
    else -> null
  }

/**
 * The label for one option.
 *
 * An exhaustive `when` over the pair rather than a built resource name, so an option added
 * in core without a label here is a compile error instead of a blank row on the screen.
 */
private fun optionLabel(question: Answers, option: String): Int =
  when (question to option) {
    Answers.TONE to "plain" -> R.string.persona_o_tone_plain
    Answers.TONE to "warm" -> R.string.persona_o_tone_warm
    Answers.TONE to "precise" -> R.string.persona_o_tone_precise
    Answers.TONE to "playful" -> R.string.persona_o_tone_playful
    Answers.LENGTH to "short" -> R.string.persona_o_length_short
    Answers.LENGTH to "medium" -> R.string.persona_o_length_medium
    Answers.LENGTH to "full" -> R.string.persona_o_length_full
    Answers.UNITS to "metric" -> R.string.persona_o_units_metric
    Answers.UNITS to "imperial" -> R.string.persona_o_units_imperial
    Answers.USES to "messages" -> R.string.persona_o_uses_messages
    Answers.USES to "calendar" -> R.string.persona_o_uses_calendar
    Answers.USES to "music" -> R.string.persona_o_uses_music
    Answers.USES to "lookups" -> R.string.persona_o_uses_lookups
    Answers.USES to "memory" -> R.string.persona_o_uses_memory
    Answers.USES to "phone" -> R.string.persona_o_uses_phone
    Answers.USES to "writing" -> R.string.persona_o_uses_writing
    Answers.ASK_FIRST to "send" -> R.string.persona_o_ask_first_send
    Answers.ASK_FIRST to "call" -> R.string.persona_o_ask_first_call
    Answers.ASK_FIRST to "settings" -> R.string.persona_o_ask_first_settings
    Answers.ASK_FIRST to "play" -> R.string.persona_o_ask_first_play
    Answers.ASK_FIRST to "open" -> R.string.persona_o_ask_first_open
    Answers.UNSURE to "say_so" -> R.string.persona_o_unsure_say_so
    Answers.UNSURE to "look_it_up" -> R.string.persona_o_unsure_look_it_up
    Answers.BIG to "here" -> R.string.persona_o_big_here
    Answers.BIG to "delegate" -> R.string.persona_o_big_delegate
    else -> error("no label for ${question.id}=$option")
  }

/** One pickable row: a radio button, a name, and a line about what picking it means. */
@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onPick: () -> Unit) {
  Row(
    // The whole row, not just the button: a radio button is a small target on a phone, and these
    // lists are read with a thumb.
    modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onPick)
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp, end = 8.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (detail.isNotEmpty()) {
        Text(
          detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The line under a server model's name: size, parameter count, quantisation.
 *
 * Assembled from whatever the server actually reported rather than from a format string with
 * placeholders, because any of the three can be missing - an older server does not send the
 * details block - and "20.0 GB · ·" is worse than "20.0 GB".
 */
private fun describe(model: ServerModel): String =
  listOf(
      if (model.sizeBytes > 0) "%.1f GB".format(model.sizeBytes / 1_000_000_000.0) else "",
      model.parameterSize,
      model.quantization,
    )
    .filter { it.isNotEmpty() }
    .joinToString(" · ")

/**
 * The photo permission this Android version actually uses.
 *
 * Three of them across the versions this app supports, and asking for the wrong one is denied
 * silently rather than with an error - which looks exactly like the user refusing.
 */
private fun photoPermissionName(): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    android.Manifest.permission.READ_MEDIA_IMAGES
  } else {
    @Suppress("DEPRECATION") android.Manifest.permission.READ_EXTERNAL_STORAGE
  }

/** One reasoning level, with what it costs underneath its name. */
@Composable
private fun ReasoningChoice(
  level: Reasoning,
  @StringRes label: Int,
  @StringRes detail: Int,
  selected: Reasoning,
  onPick: (Reasoning) -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected == level, onClick = { onPick(level) })
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected == level, onClick = { onPick(level) })
    Column(modifier = Modifier.weight(1f)) {
      Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
      Text(
        stringResource(detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** The name shown on a chip. Not localised: these are product names. */
private fun serverKindLabel(kind: ServerKind): String =
  when (kind) {
    ServerKind.OLLAMA -> "Ollama"
    ServerKind.VLLM -> "vLLM"
    ServerKind.UNSLOTH -> "Unsloth"
  }

/** What to type for this kind, which is the whole reason the kinds are listed separately. */
@StringRes
private fun serverKindDetail(kind: ServerKind): Int =
  when (kind) {
    ServerKind.OLLAMA -> R.string.settings_server_kind_ollama
    ServerKind.VLLM -> R.string.settings_server_kind_vllm
    ServerKind.UNSLOTH -> R.string.settings_server_kind_unsloth
  }

private fun serverKindPlaceholder(kind: ServerKind): String =
  when (kind) {
    ServerKind.OLLAMA -> "http://192.168.1.50:11434"
    ServerKind.VLLM -> "http://192.168.1.50:8000"
    ServerKind.UNSLOTH -> "http://192.168.1.50:8080"
  }

/**
 * Typing the model's name instead of picking it.
 *
 * Shown when the list could not be had. The name is taken on trust, which is the point: the
 * server has said nothing about what it holds, so the only authority left is the person who set
 * it up. It is remembered as typed, which is what stops the app falling back to the phone every
 * time the list fails again.
 */
@Composable
private fun TypedModel(picked: String, typed: Boolean, onType: (String) -> Unit) {
  var draft by rememberSaveable(picked) { mutableStateOf(picked) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      stringResource(R.string.settings_server_type_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      label = { Text(stringResource(R.string.settings_server_type_label)) },
      placeholder = { Text("unsloth/gemma-4-27b") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { onType(draft) }, enabled = draft.isNotBlank()) {
        Text(stringResource(R.string.settings_server_type_use))
      }
      if (typed && picked.isNotBlank()) {
        Text(
          stringResource(R.string.settings_server_type_using, picked),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }
    }
  }
}

private fun dialPolicyLabel(policy: DialPolicy): Int =
  when (policy) {
    DialPolicy.NEVER -> R.string.settings_dial_never
    DialPolicy.IN_THE_CAR -> R.string.settings_dial_in_the_car
    DialPolicy.ALWAYS -> R.string.settings_dial_always
  }

private fun dialPolicyDetail(policy: DialPolicy): Int =
  when (policy) {
    DialPolicy.NEVER -> R.string.settings_dial_never_detail
    DialPolicy.IN_THE_CAR -> R.string.settings_dial_in_the_car_detail
    DialPolicy.ALWAYS -> R.string.settings_dial_always_detail
  }

private fun speakWhenLabel(speak: SpeakWhen): Int =
  when (speak) {
    SpeakWhen.NEVER -> R.string.settings_speak_never
    SpeakWhen.WHEN_SPOKEN_TO -> R.string.settings_speak_spoken_to
    SpeakWhen.ALWAYS -> R.string.settings_speak_always
  }

private fun speakWhenDetail(speak: SpeakWhen): Int =
  when (speak) {
    SpeakWhen.NEVER -> R.string.settings_speak_never_detail
    SpeakWhen.WHEN_SPOKEN_TO -> R.string.settings_speak_spoken_to_detail
    SpeakWhen.ALWAYS -> R.string.settings_speak_always_detail
  }

/**
 * Opens the phone's own text-to-speech settings, where voices and engines are installed.
 *
 * Tried in order and caught rather than resolved first, like the assistant screens: package
 * visibility can hide a screen from resolveActivity that opens perfectly well.
 */
private fun openVoiceSettings(context: android.content.Context) {
  val screens =
    listOf("com.android.settings.TTS_SETTINGS", "android.settings.ACCESSIBILITY_SETTINGS")
  for (action in screens) {
    val opened =
      runCatching {
          context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          true
        }
        .getOrDefault(false)
    if (opened) return
  }
}
