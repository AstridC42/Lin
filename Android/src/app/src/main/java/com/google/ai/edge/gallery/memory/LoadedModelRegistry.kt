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

package com.google.ai.edge.gallery.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.ai.edge.gallery.data.Model
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which model is loaded right now, for code that cannot reach a ViewModel.
 *
 * The digester runs in a WorkManager worker. Workers have no activity, no navigation graph and
 * no access to `ModelManagerViewModel`, which is where every reference to a live [Model] lives -
 * and the engine is the one thing the digester cannot do without. Rather than hand the worker a
 * ViewModel it has no business holding, the screen that owns the model lifecycle says here when
 * one becomes available and when it goes.
 *
 * Also carries whether the model is mid-turn. Two generations on one engine at once is the kind
 * of contention that shows up as the user's reply stalling for no visible reason, so background
 * work asks first and comes back later if the answer is no.
 */
@Singleton
class LoadedModelRegistry @Inject constructor() {

  @Volatile private var loaded: Model? = null

  private val busy = AtomicBoolean(false)

  /** Called when a model finishes initialising, and with null when it is cleaned up. */
  fun setLoaded(model: Model?) {
    loaded = model
  }

  /** The loaded model, or null when there is none to borrow. */
  fun current(): Model? = loaded?.takeIf { it.instance != null }

  /** Marks the engine as in use for a foreground turn. */
  fun markBusy() {
    busy.set(true)
  }

  fun markIdle() {
    busy.set(false)
  }

  /** Whether the user is mid-turn, in which case background work should wait. */
  fun isBusy(): Boolean = busy.get()

  /** A model that is loaded and not currently answering, or null. */
  fun availableForBackgroundWork(): Model? = if (isBusy()) null else current()
}

/**
 * Reaches the singleton registry from a composable.
 *
 * [AssistantRoute] is a composable in a navigation graph rather than a Hilt-injected screen, and
 * the registry is an application singleton with no ViewModel to hang it on. An entry point is
 * the supported way across that gap, and keeping it next to the class it serves means the
 * awkwardness is in one place rather than spread through the UI.
 */
@Composable
fun rememberLoadedModelRegistry(): LoadedModelRegistry {
  val context = LocalContext.current
  return remember(context) {
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        LoadedModelRegistryEntryPoint::class.java,
      )
      .loadedModelRegistry()
  }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LoadedModelRegistryEntryPoint {
  fun loadedModelRegistry(): LoadedModelRegistry
}
