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
import com.lin.agent.engine.LlmBackend
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
constructor(private val backend: LlmBackend, private val saved: SavedStateHandle) : ViewModel() {

  private val _state = MutableStateFlow(ConversationState(messages = restored()))
  val state: StateFlow<ConversationState> = _state.asStateFlow()

  /** Which backend answered, for showing in the UI once there is more than one. */
  val backendId: String = backend.id

  fun send(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || _state.value.inProgress) return

    val outgoing = _state.value.messages + Message(Message.Role.USER, trimmed)
    _state.update { it.copy(messages = outgoing, inProgress = true, streaming = "", error = "") }
    persist(outgoing)

    viewModelScope.launch {
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
              it.copy(messages = complete, streaming = "", inProgress = false)
            }
            persist(complete)
          }
          is TurnEvent.Failed -> {
            // The engine's own words are kept underneath the sentence, because on a phone with no
            // computer attached they are the only account of the failure there is.
            val shown =
              if (event.detail.isBlank()) event.message else "${event.message}\n\n${event.detail}"
            _state.update { it.copy(streaming = "", inProgress = false, error = shown) }
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
