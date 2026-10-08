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
import com.lin.agent.engine.Message
import com.lin.agent.engine.TurnEvent
import com.lin.agent.engine.TurnRequest
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
constructor(private val backends: ChatBackends, private val saved: SavedStateHandle) : ViewModel() {

  private val _state = MutableStateFlow(ConversationState(messages = restored()))
  val state: StateFlow<ConversationState> = _state.asStateFlow()

  init {
    refreshReadiness()
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

  fun send(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || _state.value.inProgress) return

    // The user's message goes up immediately, before anything is resolved. Choosing a backend
    // can mean asking the server whether it is there, and a message that only appears once that
    // round trip finishes reads as a Send button that did nothing.
    val outgoing = _state.value.messages + Message(Message.Role.USER, trimmed)
    _state.update { it.copy(messages = outgoing, inProgress = true, streaming = "", error = "") }
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
      backend.converse(TurnRequest(messages = outgoing)).collect { event ->
        when (event) {
          is TurnEvent.Token -> {
            builder.append(event.text)
            _state.update { it.copy(streaming = builder.toString()) }
          }
          is TurnEvent.Done -> {
            val complete = outgoing + Message(Message.Role.ASSISTANT, event.fullText)
            _state.update {
              // backendId again: it only names the accelerator once the engine has settled on
              // one, which happens during the first turn rather than when readiness was read.
              it.copy(
                messages = complete,
                streaming = "",
                inProgress = false,
                backendId = backend.id,
              )
            }
            persist(complete)
          }
          is TurnEvent.Failed -> {
            // The engine's own words are kept underneath the sentence, because on a phone with no
            // computer attached they are the only account of the failure there is.
            val shown =
              if (event.detail.isBlank()) event.message else "${event.message}\n\n${event.detail}"
            _state.update {
              it.copy(streaming = "", inProgress = false, error = shown, backendId = backend.id)
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

  private companion object {
    const val KEY_ROLES = "conversation_roles"
    const val KEY_TEXTS = "conversation_texts"
  }
}
