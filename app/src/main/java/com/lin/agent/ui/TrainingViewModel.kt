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

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.training.ArchiveState
import com.lin.agent.core.training.TrainingArchive
import com.lin.agent.core.training.TrainingPack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrainingState(
  val keeping: Boolean = true,
  val pending: Int = 0,
  val untilNextPack: Int = 0,
  val packs: List<TrainingPack> = emptyList(),
  val totalBytes: Long = 0,
  val confirmingClear: Boolean = false,
)

/**
 * The corpus: how much has been collected, and how to take it off the phone.
 *
 * Packs are not deleted when they are sent. Sharing a file does not tell this app whether it
 * arrived anywhere, so treating a tap on Send as "safely copied" would throw away thirty
 * conversations on the strength of a guess. Deleting is a separate, deliberate action.
 */
@HiltViewModel
class TrainingViewModel
@Inject
constructor(private val archive: TrainingArchive, private val settings: SettingsRepository) :
  ViewModel() {

  private val _state = MutableStateFlow(TrainingState())
  val state: StateFlow<TrainingState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    viewModelScope.launch {
      // Null, not the live conversation's id: this screen is showing what has been collected,
      // and the conversation being had right now is part of that even though it may still grow.
      val held: ArchiveState = archive.state(currentId = null)
      _state.update {
        it.copy(
          keeping = settings.keepTranscripts.first(),
          pending = held.pending,
          untilNextPack = held.untilNextPack,
          packs = held.packs,
          totalBytes = held.totalBytes,
        )
      }
    }
  }

  fun setKeeping(on: Boolean) {
    _state.update { it.copy(keeping = on) }
    viewModelScope.launch { settings.setKeepTranscripts(on) }
  }

  /** A share sheet for one pack. The file stays here afterwards. */
  fun shareIntent(pack: TrainingPack, context: Context): Intent? {
    val shared =
      runCatching {
          FileProvider.getUriForFile(context, "${context.packageName}.documents", pack.file)
        }
        .onFailure { Log.w(TAG, "Could not share ${pack.name}", it) }
        .getOrNull() ?: return null

    return Intent(Intent.ACTION_SEND).apply {
      type = "application/x-jsonlines"
      putExtra(Intent.EXTRA_STREAM, shared)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  fun delete(pack: TrainingPack) {
    _state.update { current -> current.copy(packs = current.packs - pack) }
    viewModelScope.launch {
      archive.delete(pack)
      reload()
    }
  }

  /** Throwing away a corpus is worth a confirmation; it cannot be collected again. */
  fun askToClear() = _state.update { it.copy(confirmingClear = true) }

  fun cancelClear() = _state.update { it.copy(confirmingClear = false) }

  fun clearEverything() {
    _state.update { it.copy(confirmingClear = false) }
    viewModelScope.launch {
      archive.deleteEverything()
      reload()
    }
  }

  private companion object {
    const val TAG = "LinTrainingViewModel"
  }
}
