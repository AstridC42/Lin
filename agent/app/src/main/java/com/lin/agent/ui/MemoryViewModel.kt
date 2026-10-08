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
import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryState(
  val memories: List<Memory> = emptyList(),
  val loading: Boolean = true,
  /** Set while the user is being asked to confirm forgetting everything. */
  val confirmingForgetAll: Boolean = false,
)

/**
 * Everything Lin has kept, and the means to take any of it back.
 *
 * Built before anything writes to memory unprompted, which is the order that matters. A digest
 * that files away what it thinks is worth keeping is only acceptable if the user can see what it
 * decided and undo it; shipping the writing first and the reading later would mean a period
 * where the app holds things about somebody that they cannot look at.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(private val memories: MemoryStore) : ViewModel() {

  private val _state = MutableStateFlow(MemoryState())
  val state: StateFlow<MemoryState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    viewModelScope.launch {
      val held = memories.all()
      _state.update { it.copy(memories = held, loading = false) }
    }
  }

  /**
   * Deletes one, immediately and without a confirmation.
   *
   * Deliberately unguarded. The risk here runs the other way round from most deletes: what is
   * being removed is something the app decided to keep about a person, and making them confirm
   * twice to get rid of it puts the friction on the wrong side. Losing a note they wanted is
   * recoverable - they can say it again.
   */
  fun forget(memory: Memory) {
    // Removed from the list first so the row disappears under the thumb rather than after a
    // round trip to the database.
    _state.update { current -> current.copy(memories = current.memories - memory) }
    viewModelScope.launch {
      memories.forget(memory.id)
      reload()
    }
  }

  /** Forgetting everything is the one that asks, because it cannot be undone one row at a time. */
  fun askToForgetAll() = _state.update { it.copy(confirmingForgetAll = true) }

  fun cancelForgetAll() = _state.update { it.copy(confirmingForgetAll = false) }

  fun forgetAll() {
    val held = _state.value.memories
    _state.update { it.copy(memories = emptyList(), confirmingForgetAll = false) }
    viewModelScope.launch {
      held.forEach { memories.forget(it.id) }
      reload()
    }
  }
}
