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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.common.MemoryWarningAlert
import com.google.ai.edge.gallery.ui.common.isMemoryLow
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.voice.WakeWordService
import com.google.ai.edge.gallery.voice.WakeWordSettings

/**
 * The app's start destination.
 *
 * Its job is everything that has to happen before the assistant chat can render: accepting the
 * terms of service (which upstream did on the home screen, and which is now the first screen the
 * user sees), waiting for the model allowlist, and choosing which model to load.
 *
 * @param onOpenGallery navigates to the upstream capability gallery, which is still reachable and
 *   unchanged.
 * @param onExit called when the user navigates back from the assistant; there is nothing above it
 *   in the back stack, so this normally sends the app to the background.
 * @param initialQuery a question to ask straight away, e.g. from the system assist gesture.
 * @param initialImageUris images shared into the app to attach to that first message.
 * @param startListening open the microphone straight away, for a hands-free summon.
 */
@Composable
fun AssistantRoute(
  modelManagerViewModel: ModelManagerViewModel,
  onOpenGallery: () -> Unit,
  onOpenModels: () -> Unit,
  onExit: () -> Unit,
  initialQuery: String? = null,
  initialImageUris: List<String> = listOf(),
  startListening: Boolean = false,
  tosViewModel: TosViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val assistantTask = modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.ASSISTANT)
  val models = assistantTask?.task?.models ?: listOf()
  val repository = modelManagerViewModel.dataStoreRepository

  // A crash recorded by the previous run, shown once and then discarded.
  var lastCrash by remember { mutableStateOf(CrashReport.read(context)) }

  // The model the previous launch began loading and never finished. Read once, before anything
  // clears it: a value here means that launch died mid-load, and loading the same model again
  // would simply repeat it. See AssistantSettings.loading_model_name.
  var interruptedModel by remember { mutableStateOf(repository.readAssistantSettings().loadingModelName) }
  var memoryWarningModel by remember { mutableStateOf<Model?>(null) }

  // Loading a model is only safe to start once nothing above is holding the screen. Anything that
  // needs the user to act first has to keep the chat from rendering, because rendering it is what
  // starts the load.
  val blockedBeforeLoading = lastCrash != null || interruptedModel.isNotEmpty()

  // Pick a model as soon as the allowlist is in: the one used last if it is still around,
  // otherwise the first one already on the device, otherwise the first one at all so the chat can
  // at least offer to download it.
  LaunchedEffect(models.size, uiState.loadingModelAllowlist, blockedBeforeLoading) {
    if (models.isEmpty() || blockedBeforeLoading) return@LaunchedEffect
    val selected = modelManagerViewModel.uiState.value.selectedModel
    if (models.any { it.name == selected.name }) return@LaunchedEffect

    val lastUsed = repository.readAssistantSettings().lastModelName
    val model =
      models.firstOrNull { it.name == lastUsed }
        ?: models.firstOrNull { modelManagerViewModel.isModelDownloaded(it) }
        ?: models.first()

    // Upstream only ever loaded a model after the user picked one, and warned there if it looked
    // too large for the device. Restoring a model at launch skips that entirely, so the same check
    // has to happen here - otherwise the first the user hears of it is the app closing.
    if (
      modelManagerViewModel.isModelDownloaded(model) &&
        isMemoryLow(context, model) &&
        !repository.readAssistantSettings().memoryWarningAccepted
    ) {
      memoryWarningModel = model
      return@LaunchedEffect
    }
    modelManagerViewModel.selectModel(model)
  }

  // Mark a model as being loaded for as long as that is true, so a launch that does not survive it
  // can tell. Cleared once loading ends - on success, and on a reported error too, since an error
  // the app lived to report is not the case this guards against.
  val selectedName = uiState.selectedModel.name
  val initializationStatus = uiState.modelInitializationStatus[selectedName]?.status
  LaunchedEffect(selectedName, initializationStatus, blockedBeforeLoading) {
    if (selectedName.isEmpty() || blockedBeforeLoading) return@LaunchedEffect
    val marker =
      loadingMarkerFor(
        modelName = selectedName,
        downloadStatus = uiState.modelDownloadStatus[selectedName]?.status,
        initializationStatus = initializationStatus,
      )
    val current = repository.readAssistantSettings()
    if (current.loadingModelName != marker) {
      repository.saveAssistantSettings(current.toBuilder().setLoadingModelName(marker).build())
    }
  }

  // Hold the process for as long as there is a loaded model to hold, and only then. Keyed on the
  // initialization status so it follows the model: it starts when one finishes loading and goes
  // away again when the model does, rather than holding an empty process because a setting is on.
  LaunchedEffect(initializationStatus, selectedName) {
    if (initializationStatus == ModelInitializationStatusType.INITIALIZED) {
      ResidentAssistantService.startIfEnabled(context)
    } else {
      ResidentAssistantService.stop(context)
    }
  }

  // Bring the wake word listener back after a reboot or a swipe-away. The service is the only
  // thing that knows how to listen and it does not survive the process, so without this the
  // setting would silently mean nothing until the tile was tapped again.
  LaunchedEffect(Unit) {
    val settings = repository.readAssistantSettings()
    if (settings.wakeWordEnabled) {
      WakeWordService.start(context, WakeWordSettings.phraseOrDefault(settings.wakeWordPhrase))
    }
  }

  // Remember the model across launches so the assistant comes back the way it was left.
  val selectedModelName = uiState.selectedModel.name
  LaunchedEffect(selectedModelName) {
    if (selectedModelName.isNotEmpty() && models.any { it.name == selectedModelName }) {
      val repository = modelManagerViewModel.dataStoreRepository
      repository.saveAssistantSettings(
        repository.readAssistantSettings().toBuilder().setLastModelName(selectedModelName).build()
      )
    }
  }

  when {
    showTosDialog ->
      AppTosDialog(
        onTosAccepted = {
          showTosDialog = false
          tosViewModel.acceptTos()
        }
      )

    // Both of these have to come before the chat: rendering the chat is what starts a model
    // loading, and each of these is a reason not to start one yet.
    interruptedModel.isNotEmpty() ->
      AssistantRecoveryState(
        modelName = interruptedModel,
        onTryAgain = {
          repository.saveAssistantSettings(
            repository.readAssistantSettings().toBuilder().setLoadingModelName("").build()
          )
          interruptedModel = ""
        },
        onPickAnother = {
          // Forget both the stuck model and the choice that led to it, so the next launch starts
          // from the top of the list rather than straight back into the same load.
          repository.saveAssistantSettings(
            repository
              .readAssistantSettings()
              .toBuilder()
              .setLoadingModelName("")
              .setLastModelName("")
              .setMemoryWarningAccepted(false)
              .build()
          )
          interruptedModel = ""
          onOpenGallery()
        },
      )

    lastCrash != null ->
      AssistantCrashState(
        report = lastCrash.orEmpty(),
        onDismiss = {
          CrashReport.clear(context)
          lastCrash = null
        },
      )

    memoryWarningModel != null ->
      MemoryWarningAlert(
        onProceeded = {
          val model = memoryWarningModel
          repository.saveAssistantSettings(
            repository.readAssistantSettings().toBuilder().setMemoryWarningAccepted(true).build()
          )
          memoryWarningModel = null
          model?.let { modelManagerViewModel.selectModel(it) }
        },
        onDismissed = {
          memoryWarningModel = null
          onOpenGallery()
        },
      )

    assistantTask == null || models.isEmpty() ->
      AssistantStartupState(
        loading = uiState.loadingModelAllowlist,
        error = uiState.loadingModelAllowlistError,
        onRetry = {
          modelManagerViewModel.clearLoadModelAllowlistError()
          modelManagerViewModel.loadModelAllowlist()
        },
        onOpenGallery = onOpenGallery,
      )
    else ->
      assistantTask.MainScreen(
        data =
          CustomTaskDataForBuiltinTask(
            modelManagerViewModel = modelManagerViewModel,
            onNavUp = onExit,
            initialQuery = initialQuery,
            initialImageUris = initialImageUris,
            startListening = startListening,
            onOpenGallery = onOpenGallery,
            onOpenModels = onOpenModels,
          )
      )
  }
}

/**
 * What to record in `AssistantSettings.loading_model_name` for the state a model is currently in.
 *
 * The name is recorded only while a load could still be in flight, and cleared the moment it can
 * no longer be: a load that finished, a load that failed in a way the app survived to report, or a
 * model that is not downloaded and so was never loaded at all. Only a process that disappears
 * between those two points leaves the name behind, which is exactly the case the marker is for.
 */
internal fun loadingMarkerFor(
  modelName: String,
  downloadStatus: ModelDownloadStatusType?,
  initializationStatus: ModelInitializationStatusType?,
): String =
  when {
    modelName.isEmpty() -> ""
    initializationStatus == ModelInitializationStatusType.INITIALIZED -> ""
    // An error that reached the UI means the app outlived the failure and can say so itself.
    initializationStatus == ModelInitializationStatusType.ERROR -> ""
    downloadStatus == ModelDownloadStatusType.SUCCEEDED -> modelName
    else -> ""
  }

/**
 * Shown when the previous launch died while loading a model.
 *
 * Loading a model too large for the device is killed by the kernel, not raised as an exception, so
 * there is nothing to catch and nothing recorded. With the assistant as the app's first screen and
 * the remembered model loaded on sight, that repeats on every launch: the app closes instantly,
 * every time, with no way in. This is the way in.
 */
@Composable
private fun AssistantRecoveryState(
  modelName: String,
  onTryAgain: () -> Unit,
  onPickAnother: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      stringResource(R.string.assistant_recovery_title),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )
    Text(
      stringResource(R.string.assistant_recovery_content, modelName),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Button(onClick = onPickAnother) {
      Text(stringResource(R.string.assistant_recovery_pick_another))
    }
    TextButton(onClick = onTryAgain) { Text(stringResource(R.string.assistant_retry)) }
  }
}

/**
 * Shows the crash recorded by the previous run.
 *
 * The text is selectable on purpose. The app is installed on a phone with no computer attached, so
 * copying the trace into a message is the only way it can be reported at all.
 */
@Composable
private fun AssistantCrashState(report: String, onDismiss: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      stringResource(R.string.assistant_crash_title),
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      stringResource(R.string.assistant_crash_content),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer(modifier = Modifier.weight(1f)) {
      Text(
        report,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    }
    Row(
      modifier = Modifier.align(Alignment.End),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Selecting sixteen thousand characters of stack trace by dragging on a phone is not a
      // realistic way to report anything, and this report is the only account of what happened -
      // there is no computer attached to run logcat on.
      val clipboard = LocalClipboardManager.current
      TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
        Text(stringResource(R.string.assistant_crash_copy))
      }
      Button(onClick = onDismiss) { Text(stringResource(R.string.assistant_crash_continue)) }
    }
  }
}

/**
 * Shown while the model list is loading, when loading it failed, or when it came back empty.
 *
 * The failure case has to be visible here. This is the app's first screen, so a state that only
 * ever renders a spinner turns any allowlist failure into an app that hangs on launch with nothing
 * to tap and no reason given.
 */
@Composable
private fun AssistantStartupState(
  loading: Boolean,
  error: String,
  onRetry: () -> Unit,
  onOpenGallery: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    when {
      loading -> {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.size(24.dp),
        )
        Text(
          stringResource(R.string.loading_model_list),
          style = MaterialTheme.typography.bodyMedium,
        )
      }

      error.isNotEmpty() -> {
        Text(
          stringResource(R.string.assistant_model_list_failed),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
        )
        Text(
          error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.assistant_retry)) }
        TextButton(onClick = onOpenGallery) {
          Text(stringResource(R.string.assistant_open_gallery))
        }
      }

      else -> {
        Text(
          stringResource(R.string.assistant_no_models),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenGallery) {
          Text(stringResource(R.string.assistant_open_gallery))
        }
      }
    }
  }
}
