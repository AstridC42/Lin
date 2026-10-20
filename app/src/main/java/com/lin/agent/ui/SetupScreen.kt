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
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.core.models.DownloadState
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelRole
import com.lin.agent.core.setup.SetupStep
import com.lin.agent.core.setup.checkedSteps
import com.lin.agent.core.setup.missingEssentials
import com.lin.agent.core.setup.nextStep
import com.lin.agent.core.setup.progress
import com.lin.agent.screen.accessibilityGranted
import com.lin.agent.screen.openAccessibilitySettings
import com.lin.agent.voice.linIsTheAssistant
import com.lin.agent.voice.openAppearOverOtherApps
import com.lin.agent.voice.openAssistantSettings
import com.lin.agent.voice.canAppearOverOtherApps

/**
 * The first-run setup: one step per thing Lin needs, each saying what it is for, checking
 * itself live, and opening the right place to allow it. Steps already done are skipped by
 * Next. Can be run again from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var at by rememberSaveable { mutableIntStateOf(0) }
  val step = SetupStep.entries[at.coerceIn(SetupStep.entries.indices)]

  // Re-checked whenever Lin comes back to the front, which is how a grant made in the
  // system's own settings shows up here.
  var tick by remember { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
    lifecycle.lifecycle.addObserver(observer)
    onDispose { lifecycle.lifecycle.removeObserver(observer) }
  }
  val done = remember(tick, state) { doneSteps(context, state) }

  val askPermissions =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

  fun finish() {
    viewModel.finishSetup()
    onDone()
  }

  Scaffold(
    topBar = {
      Column {
        TopAppBar(
          title = { Text(stringResource(R.string.setup_title)) },
          actions = {
            if (step != SetupStep.DONE) TextButton(onClick = { at = SetupStep.DONE.ordinal }) { Text(stringResource(R.string.setup_skip_all)) }
          },
        )
        val (count, total) = progress(done)
        LinearProgressIndicator(progress = { count / total.toFloat() }, modifier = Modifier.fillMaxWidth())
      }
    },
    bottomBar = {
      if (step != SetupStep.DONE) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          TextButton(enabled = at > 0, onClick = { at-- }) { Text(stringResource(R.string.setup_back)) }
          Button(onClick = { at = nextStep(step, done).ordinal }) {
            Text(stringResource(if (step in done || step == SetupStep.WELCOME) R.string.setup_next else R.string.setup_later))
          }
        }
      }
    },
    modifier = Modifier.imePadding(),
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (step != SetupStep.WELCOME && step != SetupStep.DONE) {
          Text(
            stringResource(if (step.essential) R.string.setup_needed else R.string.setup_optional),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(stringResource(titleOf(step)), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(detailOf(step)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (step in done) Status(R.string.setup_done_here)

        when (step) {
          SetupStep.WELCOME -> Unit
          SetupStep.BRAIN -> BrainStep(state, viewModel)
          SetupStep.NOTIFICATIONS ->
            Action(R.string.setup_allow) {
              if (android.os.Build.VERSION.SDK_INT >= 33) askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
              else open(context, AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
          SetupStep.VOICE -> VoiceStep(state, viewModel) { askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
          SetupStep.ASSISTANT -> {
            Action(R.string.setup_open_assistant) { openAssistantSettings(context) }
            Text(stringResource(R.string.setup_overlay_detail), style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canAppearOverOtherApps(context)) Status(R.string.setup_overlay_done)
            else OutlinedButton(onClick = { openAppearOverOtherApps(context) }) { Text(stringResource(R.string.setup_overlay_allow)) }
          }
          SetupStep.PHONE ->
            Action(R.string.setup_allow) {
              askPermissions.launch(
                arrayOf(
                  Manifest.permission.READ_CONTACTS,
                  Manifest.permission.CALL_PHONE,
                  Manifest.permission.READ_CALENDAR,
                  Manifest.permission.ACCESS_COARSE_LOCATION,
                )
              )
            }
          SetupStep.MESSAGES -> Action(R.string.setup_open_settings) { open(context, AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
          SetupStep.SOUND -> Action(R.string.setup_open_settings) { open(context, AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
          SetupStep.PHOTOS -> Action(R.string.setup_allow) { askPermissions.launch(arrayOf(photoPermission())) }
          SetupStep.SCREEN -> Action(R.string.setup_open_settings) { openAccessibilitySettings(context) }
          SetupStep.ALARMS ->
            Action(R.string.setup_open_settings) {
              open(context, AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            }
          SetupStep.BATTERY ->
            Action(R.string.setup_allow) {
              if (!open(context, AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) {
                open(context, AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
              }
            }
          SetupStep.NEARBY -> Action(R.string.setup_allow) { askPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
          SetupStep.TOKEN -> TokenStep(state, viewModel, context)
          SetupStep.DONE -> Summary(done, onPick = { at = it.ordinal }, onFinish = ::finish)
        }
      }
    }
  }
}

@Composable
private fun Status(@StringRes text: Int) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text(stringResource(text), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun Action(@StringRes label: Int, onClick: () -> Unit) {
  Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
}

/** Where answers come from: the user's own server, a model on the phone, or both. */
@Composable
private fun ColumnScope.BrainStep(state: SettingsState, viewModel: SettingsViewModel) {
  Card(R.string.setup_brain_server) {
    OutlinedTextField(
      value = state.ollamaUrl,
      onValueChange = viewModel::onUrlChanged,
      label = { Text(stringResource(R.string.setup_server_url)) },
      placeholder = { Text("http://192.168.1.20:11434") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = state.ollamaUrl.isNotBlank(), onClick = viewModel::save) { Text(stringResource(R.string.setup_connect)) }
    when (val found = state.serverModels) {
      ServerModelsState.Checking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      is ServerModelsState.Failed -> Text(found.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      is ServerModelsState.Found -> {
        Text(stringResource(R.string.setup_pick_server_model), style = MaterialTheme.typography.bodyMedium)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          for (model in found.models) {
            FilterChip(
              selected = state.serverModel == model.name,
              onClick = {
                viewModel.pickServerModel(model.name)
                viewModel.setAnswerOnServer(true)
              },
              label = { Text(model.name) },
            )
          }
        }
      }
      ServerModelsState.Unknown -> Unit
    }
  }
  Card(R.string.setup_brain_phone) {
    Text(
      stringResource(R.string.setup_brain_phone_detail, state.memoryGb),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (line in state.models.filter { it.model.role == ModelRole.CHAT }) {
      val fits = line.model.minMemoryGb <= state.memoryGb || state.memoryGb == 0
      ModelRow(
        name = line.name,
        detail = line.detail + if (!fits) " · " + stringResource(R.string.setup_too_big) else "",
        present = line.present,
        download = line.download,
        onDownload = { viewModel.download(line.model) },
        chosen = state.chatModel == line.name,
        onUse = { viewModel.useChatModel(line.name) },
      )
    }
  }
}

@Composable
private fun ModelRow(
  name: String,
  detail: String,
  present: Boolean,
  download: DownloadState,
  onDownload: () -> Unit,
  chosen: Boolean = false,
  onUse: (() -> Unit)? = null,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      when {
        present && onUse != null && !chosen -> OutlinedButton(onClick = onUse) { Text(stringResource(R.string.model_use)) }
        present -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        download is DownloadState.Running -> Text("${download.percent}%")
        download is DownloadState.Waiting -> Text("…")
        else -> OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.download_start)) }
      }
    }
    when (download) {
      is DownloadState.Running -> LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
      is DownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      else -> Unit
    }
  }
}

/** The microphone, and Lin's own voice and hearing so it does not lean on the phone's. */
@Composable
private fun ColumnScope.VoiceStep(state: SettingsState, viewModel: SettingsViewModel, askMic: () -> Unit) {
  Action(R.string.setup_allow_mic, askMic)
  Card(R.string.setup_own_voice) {
    val voice = ModelCatalog.voices.firstOrNull { it.name == state.linVoice } ?: ModelCatalog.voices.first()
    val hearing = ModelCatalog.hearings.firstOrNull { it.name == state.linHearing } ?: ModelCatalog.hearings.first()
    for ((model, isHearing) in listOf(voice to false, hearing to true)) {
      val line = state.models.firstOrNull { it.model == model } ?: continue
      ModelRow(
        name = line.name,
        detail = line.detail,
        present = line.present,
        download = line.download,
        onDownload = { if (isHearing) viewModel.downloadHearing(model) else viewModel.download(model) },
      )
    }
  }
}

@Composable
private fun ColumnScope.TokenStep(state: SettingsState, viewModel: SettingsViewModel, context: Context) {
  OutlinedTextField(
    value = state.hfToken,
    onValueChange = viewModel::onHfTokenChanged,
    label = { Text(stringResource(R.string.settings_hf_token)) },
    placeholder = { Text("hf_…") },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    modifier = Modifier.fillMaxWidth(),
  )
  OutlinedButton(onClick = { open(context, Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/settings/tokens")) }) {
    Text(stringResource(R.string.setup_token_make))
  }
  OutlinedButton(onClick = {
    open(context, Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions"))
  }) {
    Text(stringResource(R.string.setup_token_licence))
  }
}

@Composable
private fun ColumnScope.Summary(done: Set<SetupStep>, onPick: (SetupStep) -> Unit, onFinish: () -> Unit) {
  val missing = missingEssentials(done)
  for (each in checkedSteps) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable { onPick(each) }.padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        if (each in done) Icons.Filled.CheckCircle else Icons.Filled.Info,
        contentDescription = null,
        tint = if (each in done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(22.dp),
      )
      Text(stringResource(titleOf(each)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(
        stringResource(
          when {
            each in done -> R.string.setup_state_done
            each.essential -> R.string.setup_state_needed
            else -> R.string.setup_state_skipped
          }
        ),
        style = MaterialTheme.typography.labelMedium,
        color = if (each in done) MaterialTheme.colorScheme.primary
        else if (each.essential) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (missing.isNotEmpty()) {
    Text(stringResource(R.string.setup_missing_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  }
  Spacer(Modifier.height(8.dp))
  Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_finish)) }
  Text(stringResource(R.string.setup_again_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Card(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
      content()
    }
  }
}

private fun open(context: Context, action: String, data: Uri? = null): Boolean =
  runCatching {
      context.startActivity(Intent(action).apply { if (data != null) setData(data) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    }
    .getOrDefault(false)

/** Photos are READ_MEDIA_IMAGES from Android 13, and the old storage permission before. */
private fun photoPermission(): String =
  if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

private fun granted(context: Context, vararg permissions: String) =
  permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Which steps are already done, read live from the phone. */
private fun doneSteps(context: Context, state: SettingsState): Set<SetupStep> = buildSet {
  val serverOk = state.ollamaUrl.isNotBlank() && state.serverModels is ServerModelsState.Found && state.serverModel.isNotBlank()
  val phoneModel = state.models.any { it.present && it.model.role == ModelRole.CHAT }
  if (serverOk || phoneModel || (state.ollamaUrl.isNotBlank() && state.serverModel.isNotBlank())) add(SetupStep.BRAIN)
  if (NotificationManagerCompat.from(context).areNotificationsEnabled()) add(SetupStep.NOTIFICATIONS)
  if (granted(context, Manifest.permission.RECORD_AUDIO)) add(SetupStep.VOICE)
  if (linIsTheAssistant(context) == true) add(SetupStep.ASSISTANT)
  if (granted(context, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALENDAR,
      Manifest.permission.ACCESS_COARSE_LOCATION)) add(SetupStep.PHONE)
  if (runCatching { context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context) }.getOrDefault(false)) {
    add(SetupStep.MESSAGES)
  }
  if (runCatching { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false)) {
    add(SetupStep.SOUND)
  }
  if (granted(context, photoPermission()) ||
      (android.os.Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) {
    add(SetupStep.PHOTOS)
  }
  if (accessibilityGranted(context)) add(SetupStep.SCREEN)
  if (runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(true)) add(SetupStep.ALARMS)
  if (runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)) {
    add(SetupStep.BATTERY)
  }
  if (granted(context, Manifest.permission.BLUETOOTH_CONNECT)) add(SetupStep.NEARBY)
  if (state.hfToken.isNotBlank()) add(SetupStep.TOKEN)
}

private fun titleOf(step: SetupStep) =
  when (step) {
    SetupStep.WELCOME -> R.string.setup_welcome_title
    SetupStep.BRAIN -> R.string.setup_brain_title
    SetupStep.NOTIFICATIONS -> R.string.setup_notifications_title
    SetupStep.VOICE -> R.string.setup_voice_title
    SetupStep.ASSISTANT -> R.string.setup_assistant_title
    SetupStep.PHONE -> R.string.setup_phone_title
    SetupStep.MESSAGES -> R.string.setup_messages_title
    SetupStep.SOUND -> R.string.setup_sound_title
    SetupStep.PHOTOS -> R.string.setup_photos_title
    SetupStep.SCREEN -> R.string.setup_screen_title
    SetupStep.ALARMS -> R.string.setup_alarms_title
    SetupStep.BATTERY -> R.string.setup_battery_title
    SetupStep.NEARBY -> R.string.setup_nearby_title
    SetupStep.TOKEN -> R.string.setup_token_title
    SetupStep.DONE -> R.string.setup_done_title
  }

private fun detailOf(step: SetupStep) =
  when (step) {
    SetupStep.WELCOME -> R.string.setup_welcome_detail
    SetupStep.BRAIN -> R.string.setup_brain_detail
    SetupStep.NOTIFICATIONS -> R.string.setup_notifications_detail
    SetupStep.VOICE -> R.string.setup_voice_detail
    SetupStep.ASSISTANT -> R.string.setup_assistant_detail
    SetupStep.PHONE -> R.string.setup_phone_detail
    SetupStep.MESSAGES -> R.string.setup_messages_detail
    SetupStep.SOUND -> R.string.setup_sound_detail
    SetupStep.PHOTOS -> R.string.setup_photos_detail
    SetupStep.SCREEN -> R.string.setup_screen_detail
    SetupStep.ALARMS -> R.string.setup_alarms_detail
    SetupStep.BATTERY -> R.string.setup_battery_detail
    SetupStep.NEARBY -> R.string.setup_nearby_detail
    SetupStep.TOKEN -> R.string.setup_token_detail
    SetupStep.DONE -> R.string.setup_done_detail
  }
