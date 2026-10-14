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
import com.lin.agent.core.models.CatalogModel
import com.lin.agent.core.models.DownloadState
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelDownloads
import com.lin.agent.core.models.ModelRole
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.calendar.Calendars
import com.lin.agent.engine.Reasoning
import com.lin.agent.engine.ServerKind
import com.lin.agent.engine.serverKindNamed
import com.lin.agent.engine.reasoningNamed
import com.lin.agent.engine.ModelServer
import com.lin.agent.engine.ServerModel
import com.lin.agent.engine.ServerModelsResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lin.agent.model.ChatBackends
import com.lin.agent.model.IndexState
import com.lin.agent.model.PhotoIndex
import com.lin.agent.core.memory.MemorySource
import com.lin.agent.core.memory.MemoryStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One line about a model, for the settings screen to show. */
data class ModelLine(
  val model: CatalogModel,
  val detail: String,
  val present: Boolean,
  val download: DownloadState = DownloadState.Idle,
) {
  val name: String
    get() = model.name
}

/** What the settings screen knows about the server's model list. */
sealed interface ServerModelsState {
  /** Nothing asked yet - no address, or the address was just edited. */
  data object Unknown : ServerModelsState

  data object Checking : ServerModelsState

  /** The server answered. Empty means reachable with nothing pulled, which is worth saying. */
  data class Found(val models: List<ServerModel>) : ServerModelsState

  data class Failed(val message: String) : ServerModelsState
}

data class SettingsState(
  val ollamaUrl: String = "",
  val ollamaAuthHeader: String = "",
  val memoryGb: Int = 0,
  val socModel: String = "",
  val models: List<ModelLine> = emptyList(),
  val modelsDirectory: String = "",
  val saved: Boolean = false,
  val serverModels: ServerModelsState = ServerModelsState.Unknown,
  /** The chosen server model's name, or empty. May name one the server no longer has. */
  val serverModel: String = "",
  /** True when [serverModel] was typed rather than picked from the server's list. */
  val serverModelTyped: Boolean = false,
  val answerOnServer: Boolean = false,
  val allowDirectCalls: Boolean = false,
  val spokenReplies: Boolean = true,
  val digestConversations: Boolean = false,
  /** The reasoning level, as the engine's own enum. */
  val reasoning: Reasoning = Reasoning.OFF,
  /** Which kind of server the address points at, which decides the dialect spoken to it. */
  val serverKind: ServerKind = ServerKind.OLLAMA,
  /** Whether Lin may read the calendar. Asked for here; there is no write permission at all. */
  val calendarPermitted: Boolean = false,
  val photoIndex: IndexState = IndexState.Idle,
  val photosIndexed: Int = 0,
)

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  private val settings: SettingsRepository,
  private val store: ModelStore,
  private val downloads: ModelDownloads,
  private val backends: ChatBackends,
  private val server: ModelServer,
  private val calendars: Calendars,
  private val photos: PhotoIndex,
  private val memories: MemoryStore,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsState())
  val state: StateFlow<SettingsState> = _state.asStateFlow()

  /**
   * The server probe in flight, so a second one can replace it.
   *
   * Declared above the `init` blocks rather than below, which is not style. Property initialisers
   * run in declaration order, after nothing and before whatever comes next - so a `probe` written
   * at the foot of the class would have its `= null` applied *after* the init block had started a
   * probe and stored its job here, silently discarding it. The next check would then have nothing
   * to cancel and two probes would race, with the older one free to win.
   */
  private var probe: Job? = null

  init {
    viewModelScope.launch {
      val memoryGb = store.memoryGb()
      val defaultModel = ModelCatalog.defaultChatModel(memoryGb)
      _state.update {
        it.copy(
          ollamaUrl = settings.serverUrl.first(),
          ollamaAuthHeader = settings.serverAuthHeader.first(),
          serverModel = settings.serverModelName.first(),
          serverModelTyped = settings.serverModelTyped.first(),
          answerOnServer = settings.answerOnServer.first(),
          allowDirectCalls = settings.allowDirectCalls.first(),
          spokenReplies = settings.spokenReplies.first(),
          digestConversations = settings.digestConversations.first(),
          reasoning = reasoningNamed(settings.reasoning.first()),
          serverKind = serverKindNamed(settings.serverKind.first()),
          calendarPermitted = calendars.permitted(),
          memoryGb = memoryGb,
          socModel = com.lin.agent.core.models.currentSocModel(),
          modelsDirectory = store.directory().absolutePath,
          models =
            ModelCatalog.all.map { model ->
              val gigabytes = "%.1f GB".format(model.build.sizeBytes / 1_000_000_000.0)
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
                model = model,
                detail = "$gigabytes · $role · $fit",
                present = store.isPresent(model),
              )
            },
        )
      }
      // Asked on open rather than behind a button, because the first question anybody has on this
      // screen is whether the phone can reach the server at all - and from outside the house that
      // answer changes without anything in the app changing.
      if (_state.value.ollamaUrl.isNotBlank()) checkServer()
    }
  }

  init {
    viewModelScope.launch {
      photos.watch().collect { indexing ->
        _state.update { it.copy(photoIndex = indexing) }
        // Re-counted when a run ends rather than polled: the count only moves while indexing,
        // and a query per progress tick would compete with the indexing for the same database.
        if (indexing is IndexState.Finished || indexing is IndexState.Idle) {
          _state.update { it.copy(photosIndexed = memories.countFrom(MemorySource.PHOTO)) }
        }
      }
    }
  }

  init {
    // Follow every model's download at once. Combining them keeps the screen a single state
    // object rather than one subscription per card, and it is also what notices a download
    // finishing so the conversation can pick the model up without a restart.
    viewModelScope.launch {
      val watched = ModelCatalog.all.map { model -> downloads.watch(model) }
      combine(watched) { states -> states.toList() }
        .collect { states ->
          val byName = ModelCatalog.all.map { it.name }.zip(states).toMap()
          var anyFinished = false
          _state.update { current ->
            current.copy(
              models =
                current.models.map { line ->
                  val download = byName[line.name] ?: DownloadState.Idle
                  val present = store.isPresent(line.model)
                  if (download is DownloadState.Done && present && !line.present) {
                    anyFinished = true
                  }
                  line.copy(present = present, download = download)
                }
            )
          }
          // A newly arrived model means the held engine reference is stale.
          if (anyFinished) backends.refresh()
        }
    }
  }

  fun download(model: CatalogModel) = downloads.start(model)

  fun cancelDownload(model: CatalogModel) = downloads.cancel(model)

  fun onUrlChanged(value: String) =
    // The old list belonged to the old address, so it stops being shown the moment the address is
    // edited. Leaving it up would invite picking a model from a server that is no longer named.
    _state.update {
      it.copy(ollamaUrl = value, saved = false, serverModels = ServerModelsState.Unknown)
    }

  fun onAuthHeaderChanged(value: String) =
    _state.update {
      it.copy(ollamaAuthHeader = value, saved = false, serverModels = ServerModelsState.Unknown)
    }

  /**
   * Asks the server what it has, using what is in the fields rather than what is saved.
   *
   * Deliberately the unsaved values: the point of this button is to find out whether an address
   * works before committing to it, and making somebody save a wrong address to discover it is
   * wrong is the opposite of that.
   */
  fun checkServer() {
    val current = _state.value
    // One probe at a time. Tapping again means "use what I have just typed", so the older answer
    // is not merely redundant, it is about a different address.
    probe?.cancel()
    _state.update { it.copy(serverModels = ServerModelsState.Checking) }
    probe =
      viewModelScope.launch {
        val result = server.models(current.ollamaUrl, current.ollamaAuthHeader, current.serverKind)
        _state.update {
          it.copy(
            serverModels =
              when (result) {
                is ServerModelsResult.Found -> ServerModelsState.Found(result.models)
                is ServerModelsResult.Failed -> ServerModelsState.Failed(result.message)
              }
          )
        }
      }
  }

  fun pickServerModel(name: String) = useServerModel(name, typed = false)

  /**
   * Uses a model name the user typed instead of one picked from the server's list.
   *
   * The way out of a server that will not list its models - which is a real configuration, not
   * a broken one: Unsloth's own documentation tells people to enter a model id by hand when
   * their server does not expose `/models`. Recorded as typed, because that is what lets the
   * app use the server anyway instead of falling back to the phone for ever.
   */
  fun typeServerModel(name: String) = useServerModel(name.trim(), typed = true)

  private fun useServerModel(name: String, typed: Boolean) {
    _state.update { it.copy(serverModel = name, serverModelTyped = typed) }
    viewModelScope.launch {
      settings.setServerModelName(name)
      settings.setServerModelTyped(typed)
      // The conversation holds a resolved backend, so a change here is invisible to it until it
      // re-decides. Without this, picking a model does nothing until the app is restarted.
      backends.refresh()
    }
  }

  /**
   * Turns direct calling on or off.
   *
   * [granted] is whether the phone permission is actually held - the switch asks for it as it
   * goes on, and a refusal has to leave this off rather than quietly storing an intent that
   * cannot be acted on. The policy needs both anyway; storing a true that the permission does
   * not back would only make the setting lie about what Lin can do.
   */
  /**
   * Starts or continues indexing. [granted] is whether photo access was actually given.
   *
   * Nothing is enqueued without it: a run that starts and immediately finds no photos reads as
   * the feature being broken rather than as a permission the user declined.
   */
  fun indexPhotos(granted: Boolean) {
    if (!granted) return
    photos.start()
  }

  fun stopIndexing() = photos.stop()

  fun clearPhotoIndex() {
    viewModelScope.launch {
      memories.forgetAllFrom(MemorySource.PHOTO)
      _state.update { it.copy(photosIndexed = 0) }
    }
  }

  fun setDigestConversations(on: Boolean) {
    _state.update { it.copy(digestConversations = on) }
    viewModelScope.launch { settings.setDigestConversations(on) }
  }

  /**
   * Changes which kind of server the address points at.
   *
   * The model list is cleared rather than kept. It was read from a different server speaking a
   * different dialect, and leaving a vLLM model name selected while Ollama is now configured
   * would fail every turn with a 404 that reads like the server being broken.
   */
  fun setServerKind(kind: ServerKind) {
    _state.update { it.copy(serverKind = kind, serverModels = ServerModelsState.Unknown) }
    viewModelScope.launch {
      settings.setServerKind(kind.name)
      backends.refresh()
    }
  }

  /**
   * Re-reads calendar access after the system dialog closes.
   *
   * Read from the system rather than stored. A remembered "yes" that the permission no longer
   * backs would have this screen say Lin can see the calendar while every lookup fails.
   */
  fun refreshCalendarAccess(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
    _state.update { it.copy(calendarPermitted = calendars.permitted()) }
  }

  fun setReasoning(level: Reasoning) {
    _state.update { it.copy(reasoning = level) }
    viewModelScope.launch { settings.setReasoning(level.name) }
  }

  fun setSpokenReplies(on: Boolean) {
    _state.update { it.copy(spokenReplies = on) }
    viewModelScope.launch { settings.setSpokenReplies(on) }
  }

  fun setAllowDirectCalls(allow: Boolean, granted: Boolean) {
    val effective = allow && granted
    _state.update { it.copy(allowDirectCalls = effective) }
    viewModelScope.launch { settings.setAllowDirectCalls(effective) }
  }

  fun setAnswerOnServer(onServer: Boolean) {
    _state.update { it.copy(answerOnServer = onServer) }
    viewModelScope.launch {
      settings.setAnswerOnServer(onServer)
      backends.refresh()
    }
  }

  fun save() {
    val current = _state.value
    viewModelScope.launch {
      // Trimmed because these are typed on a phone keyboard, and a trailing space in a URL is a
      // connection failure that looks like the server being down.
      settings.setServerUrl(current.ollamaUrl.trim())
      settings.setServerAuthHeader(current.ollamaAuthHeader.trim())
      _state.update { it.copy(saved = true) }
    }
    // Saving an address is also a claim that it works, so it is checked.
    if (current.ollamaUrl.isNotBlank()) checkServer()
  }

}
