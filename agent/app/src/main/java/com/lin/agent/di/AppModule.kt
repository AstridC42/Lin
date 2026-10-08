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

package com.lin.agent.di

import android.content.Context
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelRole
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.engine.FakeBackend
import com.lin.agent.engine.LlmBackend
import com.lin.agent.engine.OnDeviceBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides @Singleton
  fun provideSettings(@ApplicationContext context: Context): SettingsRepository =
    SettingsRepository(context)

  @Provides @Singleton
  fun provideModelStore(@ApplicationContext context: Context): ModelStore = ModelStore(context)

  /**
   * The backend the conversation talks to.
   *
   * A real engine when there is a model file to load, and the fake one when there is not - rather
   * than an error screen, because an app that opens and can hold a conversation is more useful
   * than one that refuses until a two-gigabyte download finishes. Which one answered is visible
   * in the UI, so this is never silently pretending.
   *
   * The model is chosen by what is actually on the device, preferring the smallest that fits this
   * phone's memory. On 12 GB that is E2B rather than E4B, for the reason recorded in the
   * catalogue.
   *
   * Nothing above this line knows which it got. That is the whole point of the interface, and it
   * is what will make adding the Ollama backend in Phase 2 a change to this function.
   */
  @Provides @Singleton
  fun provideBackend(store: ModelStore): LlmBackend {
    val present = store.available(ModelRole.CHAT)
    val chosen =
      present.minByOrNull { it.sizeBytes }
        ?: return FakeBackend(id = "no model yet", perTokenDelayMillis = 40)

    val memoryGb = store.memoryGb()
    val preferred = ModelCatalog.defaultChatModel(memoryGb)
    val model = if (preferred != null && store.isPresent(preferred)) preferred else chosen

    return OnDeviceBackend(
      modelPath = store.fileFor(model).absolutePath,
      cacheDir = store.cacheDirectory().absolutePath,
      contextTokens = model.contextTokens,
      supportsImages = model.supportsImages,
      supportsAudio = model.supportsAudio,
      id = model.name,
    )
  }
}
