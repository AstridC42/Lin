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

package com.lin.agent.model

import com.lin.agent.core.models.CatalogModel
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelRole
import com.lin.agent.core.models.ModelStore
import com.lin.agent.engine.LlmBackend
import com.lin.agent.engine.OnDeviceBackend
import javax.inject.Inject
import javax.inject.Singleton

/** Whether there is anything to talk to. */
sealed interface ChatReadiness {
  /** Nothing downloaded. The UI offers the download rather than a conversation. */
  data class NoModel(val suggested: CatalogModel?) : ChatReadiness

  data class Ready(val backend: LlmBackend, val model: CatalogModel) : ChatReadiness
}

/**
 * Decides which model answers, and says so when none can.
 *
 * There is deliberately no fallback here. For one commit this returned a stand-in backend when no
 * model was present, so that the app could always hold a conversation - and that was a mistake:
 * the point of the app is the real engine, and an echo that looks like a reply is worse than an
 * empty screen that says what is missing. The stand-in now lives in test sources, so this cannot
 * reach it even by accident.
 *
 * Resolution is lazy and re-checkable rather than decided once at injection, because the model
 * usually arrives *after* the app first opens - somebody downloads it, and the conversation has to
 * become possible without a restart.
 */
@Singleton
class ChatBackends @Inject constructor(private val store: ModelStore) {

  @Volatile private var cached: ChatReadiness? = null

  /** The current state, loading nothing until a turn actually needs it. */
  fun readiness(): ChatReadiness {
    cached?.let {
      return it
    }
    return resolve().also { cached = it }
  }

  /** Called when a download finishes, so the next turn picks the new model up. */
  @Synchronized
  fun refresh() {
    (cached as? ChatReadiness.Ready)?.let { ready ->
      // Closing the old engine matters: two loaded models is several gigabytes, and on a 12 GB
      // phone that is the difference between working and being killed.
      (ready.backend as? AutoCloseable)?.let { closeable -> runCatching { closeable.close() } }
    }
    cached = null
  }

  @Synchronized
  private fun resolve(): ChatReadiness {
    val memoryGb = store.memoryGb()
    val present = store.available(ModelRole.CHAT)
    if (present.isEmpty()) {
      return ChatReadiness.NoModel(suggested = ModelCatalog.defaultChatModel(memoryGb))
    }

    // Of what is actually here, prefer the one this phone should be running. On 12 GB that is
    // E2B even when E4B is also downloaded, for the reason recorded in the catalogue - but a user
    // who has deliberately fetched only E4B gets E4B rather than nothing.
    val preferred = ModelCatalog.defaultChatModel(memoryGb)
    val model =
      present.firstOrNull { it == preferred } ?: present.minByOrNull { it.sizeBytes } ?: present[0]

    return ChatReadiness.Ready(
      backend =
        OnDeviceBackend(
          modelPath = store.fileFor(model).absolutePath,
          cacheDir = store.cacheDirectory().absolutePath,
          contextTokens = model.contextTokens,
          supportsImages = model.supportsImages,
          supportsAudio = model.supportsAudio,
          id = model.name,
        ),
      model = model,
    )
  }
}
