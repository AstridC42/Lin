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

package com.lin.agent.history

import android.content.Context
import android.util.Log
import com.lin.agent.core.history.SavedChat
import com.lin.agent.core.history.chatsFrom
import com.lin.agent.core.history.storedChats
import com.lin.agent.core.history.withChat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The past conversations the drawer lists, kept in one file on the phone.
 *
 * Held in memory as a flow so the drawer updates the moment an answer lands, and written to disk
 * in the background after every change. Nothing leaves the phone.
 */
@Singleton
class ChatHistory @Inject constructor(@ApplicationContext context: Context) {

  private val file = File(context.filesDir, NAME)
  private val writing = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _chats = MutableStateFlow<List<SavedChat>>(emptyList())
  val chats: StateFlow<List<SavedChat>> = _chats.asStateFlow()

  /** Finished once the file has been read; nothing is written before, or it would be lost. */
  private val loaded =
    scope.launch {
      val read = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
      val fromDisk = chatsFrom(read)
      // Anything saved while the file was being read wins over what was on disk.
      _chats.update { now -> now.fold(fromDisk) { list, chat -> withChat(list, chat) } }
    }

  fun save(chat: SavedChat) {
    if (chat.lines.isEmpty()) return
    _chats.update { withChat(it, chat) }
    write()
  }

  fun delete(id: String) {
    _chats.update { list -> list.filterNot { it.id == id } }
    write()
  }

  fun find(id: String): SavedChat? = _chats.value.firstOrNull { it.id == id }

  private fun write() {
    scope.launch {
      loaded.join()
      writing.withLock {
        // Read inside the lock, so whichever write runs last writes the newest list.
        val snapshot = _chats.value
        runCatching {
            val temp = File(file.parentFile, "$NAME.tmp")
            temp.writeText(storedChats(snapshot))
            if (!temp.renameTo(file)) {
              file.writeText(temp.readText())
              temp.delete()
            }
          }
          .onFailure { Log.w(TAG, "Could not save the conversation history", it) }
      }
    }
  }

  private companion object {
    const val TAG = "LinChatHistory"
    const val NAME = "history.json"
  }
}
