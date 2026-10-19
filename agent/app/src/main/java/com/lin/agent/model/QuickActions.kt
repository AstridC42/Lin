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

import android.util.Log
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.tools.MOBILE_ACTIONS
import com.lin.agent.core.tools.QuickCommand
import com.lin.agent.core.tools.actionsFor
import com.lin.agent.core.tools.fromMobileAction
import com.lin.agent.core.tools.mobileActionsPrompt
import com.lin.agent.engine.DeviceChoice
import com.lin.agent.engine.Message
import com.lin.agent.engine.OnDeviceBackend
import com.lin.agent.engine.ToolSpec
import com.lin.agent.engine.TurnEvent
import com.lin.agent.engine.TurnRequest
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The small model that goes first: a short command is shown to MobileActions 270M on the phone,
 * and only if it has nothing - or nothing that fits - does the request go to the server or the
 * main local model.
 *
 * Runs on the CPU, where Google measured it (about 0.3 s to an answer), so it never competes
 * with the main model for the GPU. Kept loaded once used, because loading is the slow part.
 * Bounded by a deadline: a quick path that can be slow is worse than none.
 */
@Singleton
class QuickActions
@Inject
constructor(private val store: ModelStore, private val settings: SettingsRepository) {

  private val lock = Mutex()
  private var backend: OnDeviceBackend? = null

  /** Whether it is downloaded and switched on. */
  suspend fun ready(): Boolean =
    store.isPresent(ModelCatalog.MOBILE_ACTIONS) && runCatching { settings.quickActionsModel.first() }.getOrDefault(true)

  /** Lin's tool for [said], or null to let the main model have it. */
  suspend fun pick(said: String): QuickCommand? {
    val allowed = actionsFor(said)
    if (allowed.isEmpty() || !ready()) return null
    return lock.withLock {
      val engine = backend ?: load().also { backend = it }
      val events =
        withTimeoutOrNull(DEADLINE_MS) {
          engine
            .converse(
              TurnRequest(
                messages = listOf(Message(Message.Role.USER, said)),
                systemPrompt = mobileActionsPrompt(LocalDateTime.now()),
                tools = MOBILE_ACTIONS.map { ToolSpec(it.name, it.description, it.parametersSchema) },
              )
            )
            .toList()
        } ?: return@withLock null.also { Log.i(TAG, "MobileActions missed its deadline") }
      val call = events.filterIsInstance<TurnEvent.ToolCalls>().firstOrNull()?.calls?.singleOrNull()
        ?: return@withLock null
      fromMobileAction(call.name, call.arguments, allowed)
    }
  }

  /** Loaded ahead of the first command, so that one is quick too. */
  suspend fun warm() {
    if (!ready()) return
    lock.withLock {
      val engine = backend ?: load().also { backend = it }
      // Actually loads the engine, which is the slow part, now rather than on the first command.
      engine.reachable()
    }
  }

  private fun load() =
    OnDeviceBackend(
      modelPath = store.fileFor(ModelCatalog.MOBILE_ACTIONS).absolutePath,
      cacheDir = store.cacheDirectory().absolutePath,
      contextTokens = ModelCatalog.MOBILE_ACTIONS.contextTokens,
      accelerators = listOf(DeviceChoice.Accelerator.CPU),
      baseId = ModelCatalog.MOBILE_ACTIONS.name,
    )

  private companion object {
    const val TAG = "LinQuickActions"
    /** Long enough for the first load on a cold engine, short enough to never feel like a stall. */
    const val DEADLINE_MS = 4_000L
  }
}
