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
import com.lin.agent.car.CarNotification
import com.lin.agent.core.car.DialPolicy
import com.lin.agent.core.car.dialPolicyNamed
import com.lin.agent.core.privileged.ShizukuState
import com.lin.agent.core.persona.Persona
import com.lin.agent.R
import com.lin.agent.core.standing.StandingTask
import com.lin.agent.core.web.hitsFrom
import com.lin.agent.core.web.searchCall
import com.lin.agent.core.web.searchTrialReport
import com.lin.agent.core.standing.storedTasks
import com.lin.agent.core.standing.tasksFrom
import com.lin.agent.model.StandingWorker
import com.lin.agent.core.persona.personaFrom
import com.lin.agent.core.persona.storedPersona
import com.lin.agent.core.tools.disabledFrom
import com.lin.agent.engine.mcp.McpServer
import com.lin.agent.engine.mcp.hostOf
import com.lin.agent.core.web.SearchProvider
import com.lin.agent.core.web.searchProviderNamed
import com.lin.agent.core.tools.storedFrom
import com.lin.agent.core.tools.withTool
import com.lin.agent.model.ToolListing
import com.lin.agent.core.screen.TapPolicy
import com.lin.agent.core.screen.tapPolicyNamed
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.voice.SpeakWhen
import com.lin.agent.core.voice.VoiceOption
import com.lin.agent.core.voice.speakWhenNamed
import com.lin.agent.model.installedMusicApps
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

/** One app that could play music. */
data class MusicAppLine(val label: String, val packageName: String)

data class SettingsState(
  val ollamaUrl: String = "",
  /**
   * How the server model should generate, as typed. Empty means the server decides.
   *
   * Strings rather than numbers all the way to the screen, because empty is a meaningful
   * value here and no numeric type has one: zero is a real temperature and means greedy.
   */
  val serverTemperature: String = "",
  val serverTopP: String = "",
  val serverTopK: String = "",
  val serverContext: String = "",
  val serverMaxTokens: String = "",
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
  /** Where Lin may ring somebody without a tap: nowhere, in the car, or anywhere. */
  val dialPolicy: DialPolicy = DialPolicy.NEVER,
  /** Whether Lin may press send in another app. See [com.lin.agent.core.screen.TapPolicy]. */
  val tapSend: TapPolicy = TapPolicy.NEVER,
  /** Where Lin searches the web, and what that provider needs. */
  val searchProvider: SearchProvider = SearchProvider.NONE,
  val searchUrl: String = "",
  val searchKey: String = "",
  val firecrawlKey: String = "",
  val firecrawlUrl: String = "",
  val tavilyKey: String = "",
  /** Hugging Face access token for model downloads. Empty for none. */
  val hfToken: String = "",
  val webImages: Boolean = true,
  /** What a trial search actually got back, for a screen that must say facts rather than verdicts. */
  val searchTrial: String = "",
  val searchTrying: Boolean = false,
  /** Things Lin does on a schedule. See [com.lin.agent.core.standing.StandingTask]. */
  val standing: List<StandingTask> = emptyList(),
  /** Reminders Lin will send, soonest first. */
  val reminders: List<com.lin.agent.core.remind.Reminder> = emptyList(),
  /** Whether Lin may set an alarm for the exact minute, or only for roughly then. */
  val exactAlarms: Boolean = true,
  /** What the user answered about themselves. See [com.lin.agent.core.persona.Persona]. */
  val persona: Persona = emptyMap(),
  /** Every tool Lin has, for the screen that switches them off. */
  val toolListing: List<ToolListing> = emptyList(),
  /** The ones switched off. See [com.lin.agent.core.tools.disabledFrom]. */
  val disabledTools: Set<String> = emptySet(),
  /** Whether the "listen in the car" notification is kept up. */
  val carNotification: Boolean = false,
  val carChat: Boolean = false,
  /** Whether the panel may appear over the lock screen, which a car needs. */
  val answerWhenLocked: Boolean = false,
  val spokenReplies: Boolean = true,
  /** When Lin reads a reply out loud: never, when spoken to, or always. */
  val speakWhen: SpeakWhen = SpeakWhen.WHEN_SPOKEN_TO,
  /** The voices this phone can speak with, best first. Empty until the engine is up. */
  val voices: List<VoiceOption> = emptyList(),
  /** The chosen voice's engine name, or empty for whatever the engine opens with. */
  val voiceName: String = "",
  val speechRate: Float = 1.0f,
  val speechPitch: Float = 1.0f,
  /** Whether the microphone reopens after Lin has finished speaking. */
  val voiceChat: Boolean = false,
  /** Whether Lin is listening for its name. Off by default; it holds the microphone. */
  val wakeWord: Boolean = false,
  /** The downloaded chat model the user picked, or empty to let the phone decide. */
  val chatModel: String = "",
  val digestConversations: Boolean = false,
  /** The reasoning level, as the engine's own enum. */
  val reasoning: Reasoning = Reasoning.OFF,
  /** Which kind of server the address points at, which decides the dialect spoken to it. */
  val serverKind: ServerKind = ServerKind.OLLAMA,
  /** Whether Lin may read the calendar. Asked for here; there is no write permission at all. */
  val calendarPermitted: Boolean = false,
  /** Apps on this phone that can play music, for the one the user picks. */
  val musicApps: List<MusicAppLine> = emptyList(),
  /** The chosen music app's package, or empty: Lin will not guess which service to open. */
  val musicApp: String = "",
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
  private val privileged: com.lin.agent.privileged.Privileged,
  private val reminders: com.lin.agent.remind.ReminderBook,
  private val web: com.lin.agent.web.Web,
  private val tools: com.lin.agent.model.Tools,
  private val work: androidx.work.WorkManager,
  private val mcp: com.lin.agent.mcp.McpConnections,
  private val speaker: com.lin.agent.voice.Speaker,
  @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
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
          serverTemperature = settings.serverTemperature.first(),
          serverTopP = settings.serverTopP.first(),
          serverTopK = settings.serverTopK.first(),
          serverContext = settings.serverContext.first(),
          serverMaxTokens = settings.serverMaxTokens.first(),
          ollamaAuthHeader = settings.serverAuthHeader.first(),
          serverModel = settings.serverModelName.first(),
          serverModelTyped = settings.serverModelTyped.first(),
          answerOnServer = settings.answerOnServer.first(),
          allowDirectCalls = settings.allowDirectCalls.first(),
          dialPolicy = dialPolicyNamed(settings.dialPolicy.first()),
          tapSend = tapPolicyNamed(settings.tapSend.first()),
          searchProvider = searchProviderNamed(settings.searchProvider.first()),
          searchUrl = settings.searchUrl.first(),
          searchKey = settings.searchKey.first(),
          firecrawlKey = settings.firecrawlKey.first(),
          firecrawlUrl = settings.firecrawlUrl.first(),
          tavilyKey = settings.tavilyKey.first(),
          hfToken = settings.hfToken.first(),
          webImages = settings.webImages.first(),
          persona = personaFrom(settings.persona.first()),
          standing = tasksFrom(settings.standingTasks.first()),
          reminders = reminders.ahead(),
          exactAlarms = reminders.exactAllowed(),
          toolListing = tools.listing(),
          disabledTools = disabledFrom(settings.disabledTools.first()),
          carNotification = settings.carNotification.first(),
          carChat = settings.carChat.first(),
          answerWhenLocked = settings.answerWhenLocked.first(),
          spokenReplies = settings.spokenReplies.first(),
          speakWhen = speakWhenNamed(settings.speakWhen.first()),
          voiceName = settings.voiceName.first(),
          speechRate = settings.speechRate.first(),
          speechPitch = settings.speechPitch.first(),
          voiceChat = settings.voiceChat.first(),
          wakeWord = settings.wakeWord.first(),
          chatModel = settings.chatModelName.first(),
          musicApp = settings.musicApp.first(),
          musicApps =
            installedMusicApps(appContext).map { MusicAppLine(it.label, it.packageName) },
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

  /**
   * The sampling fields, written as they are typed.
   *
   * Not behind the Save button the address and header share. Those three are validated
   * together against a live server; these are five independent numbers that need no checking
   * against anything, and making somebody press Save to change a temperature would be a
   * worse surprise than a few small writes.
   */
  fun onTemperatureChanged(value: String) {
    _state.update { it.copy(serverTemperature = value) }
    viewModelScope.launch { settings.setServerTemperature(value) }
  }

  fun onTopPChanged(value: String) {
    _state.update { it.copy(serverTopP = value) }
    viewModelScope.launch { settings.setServerTopP(value) }
  }

  fun onTopKChanged(value: String) {
    _state.update { it.copy(serverTopK = value) }
    viewModelScope.launch { settings.setServerTopK(value) }
  }

  fun onServerContextChanged(value: String) {
    _state.update { it.copy(serverContext = value) }
    viewModelScope.launch { settings.setServerContext(value) }
  }

  fun onMaxTokensChanged(value: String) {
    _state.update { it.copy(serverMaxTokens = value) }
    viewModelScope.launch { settings.setServerMaxTokens(value) }
  }

  /** Back to sending nothing, so the server's own configuration decides again. */
  fun clearSampling() {
    _state.update {
      it.copy(
        serverTemperature = "",
        serverTopP = "",
        serverTopK = "",
        serverContext = "",
        serverMaxTokens = "",
      )
    }
    viewModelScope.launch {
      settings.setServerTemperature("")
      settings.setServerTopP("")
      settings.setServerTopK("")
      settings.setServerContext("")
      settings.setServerMaxTokens("")
    }
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

  /**
   * Turns listening on or off, and starts or stops the service with it.
   *
   * A refusal of the microphone leaves it off rather than stored as a yes the permission does
   * not back - the same rule as direct calling. A switch that says Lin is listening while it
   * cannot is worse than one that is plainly off.
   */
  /**
   * Chooses which downloaded model answers, or clears the choice.
   *
   * Clearing matters as much as choosing: somebody who tried the bigger model and wants the
   * phone to go back to deciding should not have to work out which one it would have picked.
   */
  fun useChatModel(name: String) {
    val chosen = if (name == _state.value.chatModel) "" else name
    _state.update { it.copy(chatModel = chosen) }
    viewModelScope.launch {
      settings.setChatModelName(chosen)
      // The conversation holds a resolved backend; without this the change is invisible until
      // the app is restarted.
      backends.refresh()
    }
  }

  /**
   * Which app plays music, or none.
   *
   * Tapping the chosen one again clears it, like the model picker above - and clearing is a
   * real choice here rather than a reset to a default: with no app chosen, Lin says it has
   * not been told which service to use instead of opening whichever it finds first.
   */
  fun useMusicApp(packageName: String) {
    val chosen = if (packageName == _state.value.musicApp) "" else packageName
    _state.update { it.copy(musicApp = chosen) }
    viewModelScope.launch { settings.setMusicApp(chosen) }
  }

  fun setWakeWord(on: Boolean, granted: Boolean) {
    val effective = on && granted
    _state.update { it.copy(wakeWord = effective) }
    viewModelScope.launch { settings.setWakeWord(effective) }
  }

  fun setSpokenReplies(on: Boolean) {
    _state.update { it.copy(spokenReplies = on) }
    viewModelScope.launch { settings.setSpokenReplies(on) }
  }

  /**
   * Which dialling choice is waiting on the permission dialog.
   *
   * Held here rather than passed through the launcher because the result callback cannot carry
   * it, and the alternative - a launcher per choice - is three launchers that can disagree.
   */
  var wantedDialPolicy: DialPolicy = DialPolicy.NEVER

  /**
   * Sets where Lin may dial straight out.
   *
   * A refusal lands on NEVER rather than on what was tapped: a stored choice the permission
   * does not back would have this screen claiming Lin can place calls it cannot, and the call
   * policy would stage them anyway. Better that the screen says what will happen.
   */
  /**
   * Switches one tool on or off.
   *
   * Written through [withTool] rather than by editing a set in place, because the screen and
   * the store have to agree: a set mutated here and then failing to write would leave a
   * switch showing a state the next turn does not honour.
   */
  fun setToolEnabled(name: String, enabled: Boolean) {
    val next = withTool(_state.value.disabledTools, name, enabled)
    _state.update { it.copy(disabledTools = next) }
    viewModelScope.launch { settings.setDisabledTools(storedFrom(next)) }
  }

  /** What each MCP server is doing, straight from the connections rather than copied. */
  val mcpStatus = mcp.status

  fun addMcpServer(name: String, url: String, header: String) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch {
      mcp.add(McpServer(name = name.trim().ifEmpty { hostOf(trimmed) }, url = trimmed, header = header.trim()))
      reload()
    }
  }

  fun removeMcpServer(url: String) {
    viewModelScope.launch {
      mcp.remove(url)
      reload()
    }
  }

  /** Asks every server what it offers again. The tool list is rebuilt from the answers. */
  fun refreshMcp() {
    viewModelScope.launch {
      mcp.refresh()
      reload()
    }
  }

  /**
   * Reads the tool list again after the servers have changed.
   *
   * Needed because the listing is a snapshot taken when this screen loaded: a server added a
   * moment ago has tools that are in the registry but not yet on the screen, and a switch
   * list that does not show them looks like the server having failed.
   */
  private suspend fun reload() {
    _state.update {
      it.copy(toolListing = tools.listing(), disabledTools = disabledFrom(settings.disabledTools.first()))
    }
  }

  fun setSearchProvider(provider: SearchProvider) {
    _state.update { it.copy(searchProvider = provider) }
    viewModelScope.launch { settings.setSearchProvider(provider.name) }
  }

  fun onSearchUrlChanged(value: String) {
    _state.update { it.copy(searchUrl = value) }
    viewModelScope.launch { settings.setSearchUrl(value) }
  }

  fun onSearchKeyChanged(value: String) {
    _state.update { it.copy(searchKey = value) }
    viewModelScope.launch { settings.setSearchKey(value) }
  }

  fun onFirecrawlKeyChanged(value: String) {
    _state.update { it.copy(firecrawlKey = value) }
    viewModelScope.launch { settings.setFirecrawlKey(value) }
  }

  fun onFirecrawlUrlChanged(value: String) {
    _state.update { it.copy(firecrawlUrl = value) }
    viewModelScope.launch { settings.setFirecrawlUrl(value) }
  }

  fun setWebImages(on: Boolean) {
    _state.update { it.copy(webImages = on) }
    viewModelScope.launch { settings.setWebImages(on) }
  }

  fun onHfTokenChanged(value: String) {
    _state.update { it.copy(hfToken = value) }
    viewModelScope.launch { settings.setHfToken(value) }
  }

  fun onTavilyKeyChanged(value: String) {
    _state.update { it.copy(tavilyKey = value) }
    viewModelScope.launch { settings.setTavilyKey(value) }
  }

  /**
   * Records one answer, or clears it when nothing is left.
   *
   * Written as it is answered rather than behind a Save button. Each question is independent
   * of the others and of anything on a server, so there is nothing to validate together -
   * and a quiz that loses your answers because you left the screen is a quiz nobody finishes.
   */
  fun setPersonaAnswer(questionId: String, values: List<String>) {
    val kept = values.map { it.trim() }.filter { it.isNotEmpty() }
    val next = _state.value.persona.toMutableMap()
    if (kept.isEmpty()) next.remove(questionId) else next[questionId] = kept
    _state.update { it.copy(persona = next) }
    viewModelScope.launch { settings.setPersona(storedPersona(next)) }
  }

  /**
   * Adds a routine, or changes one with the same id.
   *
   * The periodic check is synced on every change rather than only when the first one is
   * added, because switching the last one off should stop the phone waking every quarter of
   * an hour for nothing.
   */
  fun saveStanding(task: StandingTask) {
    val next = _state.value.standing.filterNot { it.id == task.id } + task
    writeStanding(next)
  }

  fun removeStanding(id: String) = writeStanding(_state.value.standing.filterNot { it.id == id })

  fun setStandingEnabled(id: String, enabled: Boolean) =
    writeStanding(_state.value.standing.map { if (it.id == id) it.copy(enabled = enabled) else it })

  private fun writeStanding(tasks: List<StandingTask>) {
    _state.update { it.copy(standing = tasks) }
    viewModelScope.launch { settings.setStandingTasks(storedTasks(tasks)) }
    StandingWorker.sync(work, tasks)
  }

  /**
   * Cancels a reminder from the screen.
   *
   * Here as well as in the tool because this is the list somebody checks when they do not
   * trust that a reminder was cancelled - and the answer to "did it really go?" cannot itself
   * be a sentence from a model.
   */
  fun cancelReminder(id: String) {
    _state.update { it.copy(reminders = it.reminders.filterNot { r -> r.id == id }) }
    viewModelScope.launch {
      reminders.cancel(id)
      // Read back rather than trusted: the book is the truth, and one fired while the screen
      // was open would otherwise stay on it.
      _state.update { it.copy(reminders = reminders.ahead()) }
    }
  }

  /**
   * Runs one real search and reports exactly what came back.
   *
   * Here because the alternative is guesswork. A search that returns nothing has four usual
   * causes - no provider, a wrong address, a key the service rejects, and a SearXNG that
   * serves HTML instead of JSON - and they are indistinguishable from the conversation, where
   * all four arrive as "I couldn't find anything". A status, a count and the first few bytes
   * tell them apart in one line, and it is a line somebody can read out or screenshot.
   */
  fun trySearch() {
    if (_state.value.searchTrying) return
    _state.update { it.copy(searchTrying = true, searchTrial = "") }
    viewModelScope.launch {
      val provider = _state.value.searchProvider
      val state = _state.value
      // Each provider's own address and key, exactly as the search tool picks them.
      val call =
        searchCall(
          provider = provider,
          query = "hello world",
          baseUrl =
            when (provider) {
              SearchProvider.SEARXNG -> state.searchUrl
              SearchProvider.FIRECRAWL -> state.firecrawlUrl
              else -> ""
            },
          apiKey =
            when (provider) {
              SearchProvider.BRAVE -> state.searchKey
              SearchProvider.FIRECRAWL -> state.firecrawlKey
              SearchProvider.TAVILY -> state.tavilyKey
              else -> ""
            },
        )
      val said =
        if (call == null) {
          appContext.getString(R.string.settings_search_try_unset)
        } else {
          val got = runCatching { web.send(call) }
          got.fold(
            onSuccess = {
              searchTrialReport(it.status, hitsFrom(provider, it.body).size, it.body, call.url)
            },
            onFailure = {
              appContext.getString(
                R.string.settings_search_try_unreachable,
                it.message ?: it::class.java.simpleName,
              )
            },
          )
        }
      _state.update { it.copy(searchTrying = false, searchTrial = said) }
    }
  }

  /** Opens the system screen where exact alarms are allowed, if this phone has one. */
  fun askForExactAlarms() {
    val intent = reminders.exactPermissionIntent() ?: return
    runCatching {
      appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  fun clearPersona() {
    _state.update { it.copy(persona = emptyMap()) }
    viewModelScope.launch { settings.setPersona("") }
  }

  fun setTapSend(policy: TapPolicy) {
    _state.update { it.copy(tapSend = policy) }
    viewModelScope.launch { settings.setTapSend(policy.name) }
  }

  /**
   * What Shizuku is doing, asked fresh each time.
   *
   * None of it is stable enough to hold: Shizuku stops when the phone restarts, and the
   * permission is granted in Shizuku's own window rather than in this app - so the screen
   * reads it again every time it comes back to the front.
   */
  fun shizukuState(): ShizukuState = privileged.state()

  fun requestShizuku(): Boolean = privileged.request()

  fun openShizuku(): Boolean = privileged.openShizuku()

  fun openWhereToGetShizuku(): Boolean = privileged.openWhereToGetShizuku()

  fun setDialPolicy(policy: DialPolicy, granted: Boolean) {
    val effective = if (policy == DialPolicy.NEVER || granted) policy else DialPolicy.NEVER
    _state.update {
      it.copy(dialPolicy = effective, allowDirectCalls = effective == DialPolicy.ALWAYS)
    }
    viewModelScope.launch {
      settings.setDialPolicy(effective.name)
      // The old switch is kept in step rather than left behind: it is what the reading of the
      // new setting falls back to, so a stale "true" there would resurrect ALWAYS.
      settings.setAllowDirectCalls(effective == DialPolicy.ALWAYS)
    }
  }

  /**
   * The voices the engine has, asked for when the voice section is opened.
   *
   * Not at start-up: the text-to-speech engine takes a moment to come up, and asking it
   * before it has would hand back an empty list that nothing would ever refresh.
   */
  fun loadVoices() {
    viewModelScope.launch { _state.update { it.copy(voices = speaker.voices()) } }
  }

  fun setSpeakWhen(speak: SpeakWhen) {
    _state.update { it.copy(speakWhen = speak, spokenReplies = speak != SpeakWhen.NEVER) }
    viewModelScope.launch {
      settings.setSpeakWhen(speak.name)
      // The old switch is kept in step, because it is what the new setting falls back to.
      settings.setSpokenReplies(speak != SpeakWhen.NEVER)
    }
  }

  /** Picks a voice, and says something in it - a name is not something anybody can judge. */
  fun useVoice(name: String, sample: String) {
    _state.update { it.copy(voiceName = name) }
    viewModelScope.launch {
      settings.setVoiceName(name)
      speaker.preview(name, sample)
    }
  }

  fun setVoiceChat(on: Boolean) {
    _state.update { it.copy(voiceChat = on) }
    viewModelScope.launch { settings.setVoiceChat(on) }
  }

  fun setSpeechRate(rate: Float) {
    _state.update { it.copy(speechRate = rate) }
    viewModelScope.launch { settings.setSpeechRate(rate) }
  }

  fun setSpeechPitch(pitch: Float) {
    _state.update { it.copy(speechPitch = pitch) }
    viewModelScope.launch { settings.setSpeechPitch(pitch) }
  }

  /** Says the sample in whatever is currently chosen, after a speed or pitch change. */
  fun previewVoice(sample: String) {
    viewModelScope.launch { speaker.preview(_state.value.voiceName, sample) }
  }

  fun setCarNotification(on: Boolean) {
    _state.update { it.copy(carNotification = on) }
    viewModelScope.launch {
      settings.setCarNotification(on)
      CarNotification.show(appContext, on)
    }
  }

  fun setCarChat(on: Boolean) {
    _state.update { it.copy(carChat = on) }
    viewModelScope.launch {
      settings.setCarChat(on)
      if (on) com.lin.agent.car.CarChat.greet(appContext) else com.lin.agent.car.CarChat.cancel(appContext)
    }
  }

  fun setAnswerWhenLocked(on: Boolean) {
    _state.update { it.copy(answerWhenLocked = on) }
    viewModelScope.launch { settings.setAnswerWhenLocked(on) }
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
