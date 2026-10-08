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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelRole
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line about a model, for the settings screen to show. */
data class ModelLine(val name: String, val detail: String, val present: Boolean)

data class SettingsState(
  val ollamaUrl: String = "",
  val ollamaAuthHeader: String = "",
  val memoryGb: Int = 0,
  val socModel: String = "",
  val models: List<ModelLine> = emptyList(),
  val modelsDirectory: String = "",
  val saved: Boolean = false,
)

@HiltViewModel
class SettingsViewModel
@Inject
constructor(private val settings: SettingsRepository, private val store: ModelStore) : ViewModel() {

  private val _state = MutableStateFlow(SettingsState())
  val state: StateFlow<SettingsState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val memoryGb = store.memoryGb()
      val defaultModel = ModelCatalog.defaultChatModel(memoryGb)
      _state.update {
        it.copy(
          ollamaUrl = settings.serverUrl.first(),
          ollamaAuthHeader = settings.serverAuthHeader.first(),
          memoryGb = memoryGb,
          socModel = com.lin.agent.core.models.currentSocModel(),
          modelsDirectory = store.directory().absolutePath,
          models =
            ModelCatalog.all.map { model ->
              val gigabytes = "%.1f GB".format(model.sizeBytes / 1_000_000_000.0)
              val role = if (model.role == ModelRole.CHAT) "chat" else "embedding"
              val fit =
                when {
                  model.minMemoryGb > memoryGb -> "needs ${model.minMemoryGb} GB"
                  model == defaultModel -> "default"
                  !ModelCatalog.isComfortable(model, memoryGb) ->
                    "at this phone's limit"
                  else -> "available"
                }
              ModelLine(
                name = model.name,
                detail = "$gigabytes · $role · $fit",
                present = store.isPresent(model),
              )
            },
        )
      }
    }
  }

  fun onUrlChanged(value: String) = _state.update { it.copy(ollamaUrl = value, saved = false) }

  fun onAuthHeaderChanged(value: String) =
    _state.update { it.copy(ollamaAuthHeader = value, saved = false) }

  fun save() {
    val current = _state.value
    viewModelScope.launch {
      // Trimmed because these are typed on a phone keyboard, and a trailing space in a URL is a
      // connection failure that looks like the server being down.
      settings.setServerUrl(current.ollamaUrl.trim())
      settings.setServerAuthHeader(current.ollamaAuthHeader.trim())
      _state.update { it.copy(saved = true) }
    }
  }
}
