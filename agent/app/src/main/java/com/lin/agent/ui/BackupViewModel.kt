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
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.core.backup.BackupArchive
import com.lin.agent.core.backup.BackupFormat
import com.lin.agent.core.backup.StoredMemory
import com.lin.agent.core.backup.ToPack
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.training.TrainingArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupState(
  val memories: Int = 0,
  val packs: Int = 0,
  val busy: Boolean = false,
  /** The last thing that happened, said plainly. */
  val said: String = "",
  val ready: File? = null,
)

/**
 * Making a copy of what cannot be got back, and putting it back.
 *
 * The models are not in it. They are gigabytes and re-downloadable from a public hub, and a
 * backup nobody can email is a backup nobody makes. Neither is the photo index, which is
 * derived from photos that are still on the phone. What is in it is what has no other source:
 * the memories, the training corpus, and the settings.
 */
@HiltViewModel
class BackupViewModel
@Inject
constructor(
  private val memories: MemoryStore,
  private val training: TrainingArchive,
  private val settings: SettingsRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(BackupState())
  val state: StateFlow<BackupState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    viewModelScope.launch {
      _state.update {
        it.copy(memories = memories.count(), packs = training.packs().size)
      }
    }
  }

  fun save(context: Context) {
    if (_state.value.busy) return
    _state.update { it.copy(busy = true, said = "", ready = null) }
    viewModelScope.launch {
      val at = System.currentTimeMillis()
      val held = memories.exportAll().map { (memory, vector) -> StoredMemory(memory, vector) }
      val configuration = settings.exportAll()
      val packs = training.packs().map { it.file }

      val written =
        withContext(Dispatchers.IO) {
          runCatching {
              val directory = File(context.filesDir, FOLDER).also { it.mkdirs() }
              // One at a time. Keeping every backup ever made would quietly fill the phone
              // with copies of the same thing.
              directory.listFiles()?.forEach { it.delete() }
              val target = File(directory, BackupFormat.fileName(at))
              target.outputStream().use { out ->
                BackupArchive.write(
                  ToPack(versionOf(context), held, configuration, packs),
                  at,
                  out,
                )
              }
              target
            }
            .onFailure { Log.w(TAG, "Could not write a backup", it) }
            .getOrNull()
        }

      _state.update {
        it.copy(
          busy = false,
          ready = written,
          said =
            if (written == null) "The backup could not be written."
            else "Saved ${held.size} memories and ${packs.size} training file(s).",
        )
      }
    }
  }

  /**
   * Reads a backup the user picked and adds what is in it.
   *
   * Additive, always. The dangerous case is restoring onto a populated app, where replacing
   * would throw away everything learnt since the backup - so memories already held are
   * skipped and nothing is ever deleted. Settings are the exception and are replaced, because
   * half of an old server setup and half of a new one is a state nobody chose.
   */
  fun restore(uri: Uri, context: Context) {
    if (_state.value.busy) return
    _state.update { it.copy(busy = true, said = "") }
    viewModelScope.launch {
      val opened =
        withContext(Dispatchers.IO) {
          runCatching { context.contentResolver.openInputStream(uri)?.use { BackupArchive.read(it) } }
            .onFailure { Log.w(TAG, "Could not open the backup", it) }
            .getOrNull()
        }

      if (opened == null) {
        _state.update {
          it.copy(
            busy = false,
            said =
              "That is not a Lin backup this version can read. A backup made by a newer " +
                "version cannot be read by an older one.",
          )
        }
        return@launch
      }

      val existing = memories.all(limit = Int.MAX_VALUE)
      val toAdd = BackupFormat.notAlreadyHeld(opened.memories, existing)
      toAdd.forEach { memories.remember(it.memory, it.vector) }

      val restoredPacks =
        withContext(Dispatchers.IO) {
          var count = 0
          opened.packs.forEach { (name, text) ->
            runCatching {
                val target = File(training.packDirectory(), name)
                // Never over something already here: a pack of the same name from a different
                // phone is different thirty conversations.
                if (!target.exists()) {
                  target.writeText(text)
                  count++
                }
              }
              .onFailure { Log.w(TAG, "Could not restore $name", it) }
          }
          count
        }

      if (opened.settings.isNotBlank()) settings.importAll(opened.settings)

      val made = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(opened.manifest.createdAt))
      _state.update {
        it.copy(
          busy = false,
          said =
            "From the backup of $made: added ${toAdd.size} memories " +
              "(${opened.memories.size - toAdd.size} already here) and $restoredPacks " +
              "training file(s). Settings restored.",
        )
      }
      reload()
    }
  }

  /**
   * The app's version, for the manifest.
   *
   * Read from the package manager rather than BuildConfig, which this build does not
   * generate. It is only ever written into the backup, so a blank one costs nothing.
   */
  private fun versionOf(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
      }
      .getOrDefault("")

  /** A share sheet for the backup just made, which is how it leaves the phone. */
  fun shareIntent(context: Context): Intent? {
    val file = _state.value.ready ?: return null
    val shared =
      runCatching { FileProvider.getUriForFile(context, "${context.packageName}.documents", file) }
        .onFailure { Log.w(TAG, "Could not share the backup", it) }
        .getOrNull() ?: return null
    return Intent(Intent.ACTION_SEND).apply {
      type = "application/zip"
      putExtra(Intent.EXTRA_STREAM, shared)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  private companion object {
    const val TAG = "LinBackupViewModel"
    const val FOLDER = "backups"
  }
}
