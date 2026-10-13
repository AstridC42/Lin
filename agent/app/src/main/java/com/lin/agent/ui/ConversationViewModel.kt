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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.core.models.CatalogModel
import com.lin.agent.model.ChatBackends
import com.lin.agent.model.ChatReadiness
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.LoopEvent
import com.lin.agent.engine.Message
import com.lin.agent.engine.ToolRegistry
import com.lin.agent.engine.TurnLoop
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.voice.Asked
import com.lin.agent.core.voice.Launch
import com.lin.agent.core.voice.shouldListenOnLaunch
import com.lin.agent.core.voice.shouldSpeak
import com.lin.agent.model.DelegateTool
import com.lin.agent.voice.Dictation
import com.lin.agent.voice.Heard
import com.lin.agent.voice.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import com.lin.agent.core.memory.Digest
import com.lin.agent.model.ConversationLog
import com.lin.agent.model.DigestWorker
import com.lin.agent.model.Embedders
import com.lin.agent.model.FindPhotoTool
import com.lin.agent.model.PhoneTool
import com.lin.agent.model.RecallTool
import com.lin.agent.model.RememberTool
import com.lin.agent.core.memory.MemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen draws. */
data class ConversationState(
  val messages: List<Message> = emptyList(),
  /** The reply arriving right now, token by token. Empty when nothing is in flight. */
  val streaming: String = "",
  val inProgress: Boolean = false,
  val error: String = "",
  /** Which model is answering, shown so it is never ambiguous. Empty when none is loaded. */
  val backendId: String = "",
  /**
   * Set when there is no model on the device.
   *
   * The screen offers the download rather than a text box. There is no stand-in backend to fall
   * back to - an echo that looks like a reply is worse than a screen that says what is missing.
   */
  val missingModel: CatalogModel? = null,
  /** Set when what answered is not what was asked for - see [ChatReadiness.Ready.notice]. */
  val notice: String = "",
  /** The tool running right now, so a long delegation reads as busy rather than stuck. */
  val runningTool: String = "",
  /**
   * Tools that failed during this turn, by name.
   *
   * Shown to the user, not only fed back to the model. A failed tool used to be invisible: the
   * message went into the conversation the model sees, and a 2B model handed "that did not
   * work" will still often say it did - which is how Lin reported remembering a name it had
   * not saved. The model's account of what it did is not evidence that it did it.
   */
  val failedTools: List<String> = emptyList(),
  /** Pictures a tool found, shown directly rather than described by a model that cannot see. */
  val shows: List<String> = emptyList(),
  /** True while the microphone is open. Shown, because an open microphone must be visible. */
  val listening: Boolean = false,
  /** Words recognised so far, so speaking does not look like nothing happening. */
  val heard: String = "",
)

/**
 * One conversation, for as long as the app lives.
 *
 * Not a session scoped to a screen, which is what the previous app had and what forced every
 * capability to register on one conversation at once. Here the conversation is the app's spine and
 * screens are views onto it.
 *
 * [SavedStateHandle] is injected rather than ignored because this has to survive a fold: on a Fold
 * 7 the activity is recreated whenever the device opens or closes, and losing the conversation
 * each time would make the app unusable on its own hardware. Phase 1 persists it properly; this
 * keeps it across configuration changes already.
 */
@HiltViewModel
class ConversationViewModel
@Inject
constructor(
  private val backends: ChatBackends,
  private val phoneTool: PhoneTool,
  private val memories: MemoryStore,
  private val embedders: Embedders,
  private val dictation: Dictation,
  private val conversationLog: ConversationLog,
  private val work: WorkManager,
  private val speaker: Speaker,
  private val settings: SettingsRepository,
  private val saved: SavedStateHandle,
) : ViewModel() {

  /**
   * What the orchestrator may do, which is currently one thing.
   *
   * Built here, once, from role-scoped tools - so the prompt the model sees can only ever
   * contain what an orchestrator is allowed to ask for. The previous app assembled forty-four
   * tools into every prompt and relied on discipline to keep that appropriate.
   */
  private val registry =
    ToolRegistry(
      listOf(
        DelegateTool { backends.serverSubAgent() },
        phoneTool,
        RememberTool(memories, embedders),
        RecallTool(memories, embedders),
        FindPhotoTool(memories, embedders),
      )
    )

  private val _state = MutableStateFlow(ConversationState(messages = restored()))
  val state: StateFlow<ConversationState> = _state.asStateFlow()

  /** The microphone, so leaving or sending closes it. */
  private var listening: Job? = null

  /** How the question in flight arrived. Decides whether the answer is spoken. */
  private var asked = Asked.TYPED

  init {
    refreshReadiness()
  }

  /**
   * Called once when the app opens, with how it was opened.
   *
   * Summoned as the assistant means somebody held the power button, and there is no keyboard
   * in that gesture - so the microphone opens. Opened from the launcher, it never does.
   */
  fun onLaunched(launch: Launch, microphoneGranted: Boolean) {
    if (shouldListenOnLaunch(launch, microphoneGranted)) startListening()
  }

  /** Opens the microphone and sends what it hears. */
  fun startListening() {
    if (_state.value.listening) return
    speaker.stop()
    listening?.cancel()
    _state.update { it.copy(listening = true, heard = "", error = "") }
    listening =
      viewModelScope.launch {
        dictation.listen().collect { event ->
          when (event) {
            is Heard.Partial -> _state.update { it.copy(heard = event.text) }
            is Heard.Final -> {
              _state.update { it.copy(listening = false, heard = "") }
              asked = Asked.SPOKEN
              send(event.text, spoken = true)
            }
            is Heard.Failed ->
              _state.update { it.copy(listening = false, heard = "", error = event.message) }
          }
        }
      }
  }

  fun stopListening() {
    listening?.cancel()
    listening = null
    _state.update { it.copy(listening = false, heard = "") }
  }

  /**
   * Re-checks whether a model is available.
   *
   * Called on open and after a download finishes, because the usual sequence is that the app is
   * installed before the model exists - so the conversation has to become possible without a
   * restart.
   */
  fun refreshReadiness(afterDownload: Boolean = false) {
    viewModelScope.launch {
      if (afterDownload) backends.refresh()
      when (val readiness = backends.readiness()) {
        is ChatReadiness.NoModel ->
          _state.update {
            it.copy(missingModel = readiness.suggested, backendId = "", notice = "")
          }
        is ChatReadiness.Ready ->
          _state.update {
            it.copy(
              missingModel = null,
              backendId = readiness.backend.id,
              notice = readiness.notice,
            )
          }
      }
    }
  }

  fun send(text: String, spoken: Boolean = false) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || _state.value.inProgress) return
    asked = if (spoken) Asked.SPOKEN else Asked.TYPED
    speaker.stop()

    // The user's message goes up immediately, before anything is resolved. Choosing a backend
    // can mean asking the server whether it is there, and a message that only appears once that
    // round trip finishes reads as a Send button that did nothing.
    val outgoing = _state.value.messages + Message(Message.Role.USER, trimmed)
    _state.update {
      it.copy(
        messages = outgoing,
        inProgress = true,
        streaming = "",
        error = "",
        failedTools = emptyList(),
        shows = emptyList(),
      )
    }
    persist(outgoing)

    viewModelScope.launch {
      val readiness = backends.readiness()
      val backend =
        (readiness as? ChatReadiness.Ready)?.backend
          ?: run {
            _state.update {
              it.copy(
                inProgress = false,
                missingModel = (readiness as? ChatReadiness.NoModel)?.suggested,
                error = "There is no model to answer with yet.",
              )
            }
            return@launch
          }
      _state.update { it.copy(notice = (readiness as ChatReadiness.Ready).notice) }

      val builder = StringBuilder()
      TurnLoop(
          backend = backend,
          registry = registry,
          role = AgentRole.ORCHESTRATOR,
          systemPrompt = ORCHESTRATOR_PROMPT,
        )
        .run(outgoing)
        .collect { event ->
          when (event) {
            is LoopEvent.Token -> {
              builder.append(event.text)
              _state.update { it.copy(streaming = builder.toString()) }
            }
            // Shown while it runs. A delegated task can take a minute on a big model, and
            // silence for a minute is indistinguishable from the app having given up.
            is LoopEvent.ToolStarted -> _state.update { it.copy(runningTool = event.name) }
            is LoopEvent.ToolFinished ->
              _state.update {
                it.copy(
                  runningTool = "",
                  failedTools = if (event.ok) it.failedTools else it.failedTools + event.name,
                  shows = it.shows + event.shows,
                )
              }
            is LoopEvent.Done -> {
              val complete = outgoing + Message(Message.Role.ASSISTANT, event.text)
              _state.update {
                // backendId again: it only names the accelerator once the engine has settled on
                // one, which happens during the first turn rather than when readiness was read.
                it.copy(
                  messages = complete,
                  streaming = "",
                  inProgress = false,
                  runningTool = "",
                  backendId = backend.id,
                )
              }
              persist(complete)
              if (shouldSpeak(settings.spokenReplies.first(), asked)) speaker.say(event.text)
            }
            is LoopEvent.Failed -> {
              val shown =
                if (event.detail.isBlank() || event.detail.trim() == event.message.trim()) {
                  event.message
                } else {
                  "${event.message}\n\n${event.detail}"
                }
              _state.update {
                it.copy(
                  streaming = "",
                  inProgress = false,
                  runningTool = "",
                  error = shown,
                  backendId = backend.id,
                )
              }
            }
          }
        }
    }
  }

  fun dismissError() = _state.update { it.copy(error = "") }

  // Saved as two parallel string lists rather than a serialised blob: SavedStateHandle survives a
  // configuration change, and keeping the shape trivial means a format mistake cannot throw during
  // one.
  private fun persist(messages: List<Message>) {
    saved[KEY_ROLES] = ArrayList(messages.map { it.role.name })
    saved[KEY_TEXTS] = ArrayList(messages.map { it.text })
  }

  private fun restored(): List<Message> {
    val roles: ArrayList<String> = saved[KEY_ROLES] ?: return emptyList()
    val texts: ArrayList<String> = saved[KEY_TEXTS] ?: return emptyList()
    if (roles.size != texts.size) return emptyList()
    return roles.indices.mapNotNull { i ->
      val role = Message.Role.entries.firstOrNull { it.name == roles[i] } ?: return@mapNotNull null
      Message(role, texts[i])
    }
  }

  /**
   * Called when the screen goes away, so a finished conversation can be read for memories.
   *
   * Here rather than in `onCleared`, which does not run when the app is merely backgrounded -
   * and backgrounded is what "the conversation is over" usually looks like. Enqueued rather
   * than run: the model takes long enough that doing it on the way out would hold the app
   * open, and nothing is waiting for the result.
   */
  fun onStopped() {
    val messages = _state.value.messages
    if (messages.size < Digest.MIN_MESSAGES) return
    viewModelScope.launch {
      if (!settings.digestConversations.first()) return@launch
      conversationLog.write(messages)
      work.enqueueUniqueWork(
        DIGEST_WORK,
        // Replace: a newer conversation supersedes an older one that has not been read yet,
        // and the log holds one at a time anyway.
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<DigestWorker>()
          .setConstraints(
            // Nothing waits on this, so it can wait for the phone to be idle-ish. No network
            // constraint: the model it uses is on the phone.
            Constraints.Builder()
              .setRequiresBatteryNotLow(true)
              .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
              .build()
          )
          .build(),
      )
    }
  }

  override fun onCleared() {
    // The microphone and the voice both stop with the screen. Neither is something to leave
    // running because a view model was discarded.
    stopListening()
    speaker.stop()
    super.onCleared()
  }

  private companion object {
    /**
     * Short, and that is the design rather than an omission.
     *
     * The app this replaces carried a prompt long enough to crowd out the conversation, because
     * forty-four tools each needed explaining. An orchestrator with one tool needs a sentence
     * about when to use it and a sentence about what not to lose.
     */
    const val ORCHESTRATOR_PROMPT =
      "You are Lin, an assistant running on this phone. Answer directly whenever you can. " +
        "For work that needs a much larger model - real research, long writing, heavy analysis " +
        "- call delegate. When you do, copy every condition the user stated into constraints, " +
        "word for word; never invent one they did not say. " +
        "To ring someone or text them, call phone_action; it opens the dialer or the composer " +
        "for the user to confirm, so say what you have put on screen rather than claiming it " +
        "is done. Never invent a phone number - pass the name instead. " +
        "Call remember when the user says to keep something, and recall when they refer to " +
        "something you were not told in this conversation. If recall finds nothing, say so " +
        "rather than filling the gap. " +
        "Use find_photo to look through the user\'s pictures; they appear on screen, and you " +
        "cannot see them, so never describe what is in one."

    const val DIGEST_WORK = "digest"

    const val KEY_ROLES = "conversation_roles"
    const val KEY_TEXTS = "conversation_texts"
  }
}
