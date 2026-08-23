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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

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
 */
@Composable
fun AssistantRoute(
  modelManagerViewModel: ModelManagerViewModel,
  onOpenGallery: () -> Unit,
  onExit: () -> Unit,
  initialQuery: String? = null,
  initialImageUris: List<String> = listOf(),
  tosViewModel: TosViewModel = hiltViewModel(),
) {
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val assistantTask = modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.ASSISTANT)
  val models = assistantTask?.task?.models ?: listOf()

  // Pick a model as soon as the allowlist is in: the one used last if it is still around,
  // otherwise the first one already on the device, otherwise the first one at all so the chat can
  // at least offer to download it.
  LaunchedEffect(models.size, uiState.loadingModelAllowlist) {
    if (models.isEmpty()) return@LaunchedEffect
    val selected = modelManagerViewModel.uiState.value.selectedModel
    if (models.any { it.name == selected.name }) return@LaunchedEffect

    val lastUsed = modelManagerViewModel.dataStoreRepository.readAssistantSettings().lastModelName
    val model =
      models.firstOrNull { it.name == lastUsed }
        ?: models.firstOrNull { modelManagerViewModel.isModelDownloaded(it) }
        ?: models.first()
    modelManagerViewModel.selectModel(model)
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
    assistantTask == null || models.isEmpty() ->
      AssistantStartupState(
        loading = uiState.loadingModelAllowlist || assistantTask == null,
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
            onOpenGallery = onOpenGallery,
          )
      )
  }
}

/** Shown while the model list is loading, or when it came back without any usable model. */
@Composable
private fun AssistantStartupState(loading: Boolean, onOpenGallery: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (loading) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
      Text(
        stringResource(R.string.loading_model_list),
        style = MaterialTheme.typography.bodyMedium,
      )
    } else {
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
