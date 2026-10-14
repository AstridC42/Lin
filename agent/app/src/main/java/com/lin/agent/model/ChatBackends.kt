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
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.engine.LlmBackend
import com.lin.agent.engine.ServerBackend
import com.lin.agent.engine.serverKindNamed
import com.lin.agent.engine.OnDeviceBackend
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether there is anything to talk to. */
sealed interface ChatReadiness {
  /** Nothing downloaded and no server. The UI offers the download rather than a conversation. */
  data class NoModel(val suggested: CatalogModel?) : ChatReadiness

  /**
   * Something can answer.
   *
   * [notice] is set when what answers is not what was asked for - the server was chosen and
   * could not be reached, so the phone is standing in. Silence there would be the worst option:
   * the reply would come from a 2B model while the user believed they were talking to a 32B one.
   */
  data class Ready(val backend: LlmBackend, val notice: String = "") : ChatReadiness
}

/**
 * Decides which model answers, and says so when it is not the one that was asked for.
 *
 * There is deliberately no stand-in. For one commit this returned an echo backend when no model
 * was present, so that the app could always hold a conversation - and that was a mistake: the
 * point of the app is a real model, and a reply that only looks like one is worse than a screen
 * that says what is missing. The stand-in now lives in test sources, so this cannot reach it even
 * by accident.
 *
 * Resolution is lazy and re-checkable rather than decided once at injection, because what is
 * available changes while the app is open: a download finishes, a server comes back, somebody
 * changes their mind in settings.
 */
@Singleton
class ChatBackends
@Inject
constructor(private val store: ModelStore, private val settings: SettingsRepository) {

  private val lock = Mutex()

  @Volatile private var cached: ChatReadiness? = null

  /**
   * The current state, loading nothing until a turn actually needs it.
   *
   * Suspending, which it has to be: choosing between the phone and the server means reading
   * settings and asking the server whether it is there. Doing either of those from a property
   * getter would have put a network round trip on whatever thread asked.
   */
  suspend fun readiness(): ChatReadiness {
    cached?.let {
      return it
    }
    return lock.withLock { cached ?: resolve().also { cached = it } }
  }

  /** Called when a download finishes or a setting changes, so the next turn re-decides. */
  suspend fun refresh() {
    lock.withLock {
      (cached as? ChatReadiness.Ready)?.let { ready ->
        // Closing the old engine matters: two loaded models is several gigabytes, and on a 12 GB
        // phone that is the difference between working and being killed. The server backend
        // holds nothing, so this does nothing for it.
        (ready.backend as? AutoCloseable)?.let { closeable -> runCatching { closeable.close() } }
      }
      cached = null
    }
  }

  /**
   * The server model, for a sub-agent to run on, whoever is answering the conversation.
   *
   * Separate from [readiness] on purpose. Who answers the user is the user's choice; what a
   * delegated task runs on is not the same question - the whole point of delegation is a phone
   * orchestrator reaching the bigger model, so this must be available while the conversation is
   * being answered on the phone.
   *
   * Reachability is not checked here. The sub-agent's own turn fails with a message if the
   * server has gone, and a probe before every delegation would add a round trip to a path that
   * is about to make a much longer one anyway.
   */
  suspend fun serverSubAgent(): LlmBackend? {
    val url = settings.serverUrl.first()
    val model = settings.serverModelName.first()
    if (url.isBlank() || model.isBlank()) return null
    return ServerBackend(
      baseUrl = url,
      authHeader = settings.serverAuthHeader.first(),
      modelName = model,
      kind = serverKindNamed(settings.serverKind.first()),
      assumeModelExists = settings.serverModelTyped.first(),
    )
  }

  private suspend fun resolve(): ChatReadiness {
    val onDevice = localBackend()

    if (!settings.answerOnServer.first()) return onDevice?.let { ChatReadiness.Ready(it) } ?: none()

    val url = settings.serverUrl.first()
    val model = settings.serverModelName.first()
    if (url.isBlank() || model.isBlank()) {
      return onDevice?.let {
        ChatReadiness.Ready(it, notice = "No server model is chosen, so this phone is answering.")
      } ?: none()
    }

    val server =
      ServerBackend(
        baseUrl = url,
        authHeader = settings.serverAuthHeader.first(),
        modelName = model,
        kind = serverKindNamed(settings.serverKind.first()),
        assumeModelExists = settings.serverModelTyped.first(),
      )
    // Asked once here rather than per turn. A turn against a server that has gone away still
    // fails with its own message; this is what lets the phone take over before that happens.
    if (server.reachable()) return ChatReadiness.Ready(server)

    return onDevice?.let {
      ChatReadiness.Ready(
        it,
        notice = "$model is not reachable right now, so this phone is answering.",
      )
    } ?: none()
  }

  private fun none(): ChatReadiness =
    ChatReadiness.NoModel(suggested = ModelCatalog.defaultChatModel(store.memoryGb()))

  private suspend fun localBackend(): OnDeviceBackend? {
    val present = store.available(ModelRole.CHAT)
    if (present.isEmpty()) return null

    // The user's choice first, then what this phone should be running, then whatever is here.
    // Until now only the middle rule existed and the choice was read by nothing - see
    // ModelCatalog.chatModelToUse.
    val model =
      ModelCatalog.chatModelToUse(
        present = present,
        chosenName = settings.chatModelName.first(),
        memoryGb = store.memoryGb(),
      ) ?: return null

    return OnDeviceBackend(
      modelPath = store.fileFor(model).absolutePath,
      cacheDir = store.cacheDirectory().absolutePath,
      contextTokens = model.contextTokens,
      supportsImages = model.supportsImages,
      supportsAudio = model.supportsAudio,
      baseId = model.name,
    )
  }
}
