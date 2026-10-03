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

package com.google.ai.edge.gallery.assistant

import android.content.Context
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AssistantAgentTools
import com.google.ai.edge.gallery.agent.AssistantExecutor
import com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.memory.MemoryVaultRepository
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.smarthome.HomeAssistantClient
import com.google.ai.edge.gallery.skills.SkillManager
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Wiring for the unified assistant capability. */
@Module
@InstallIn(SingletonComponent::class)
internal object AssistantTaskModule {

  /**
   * The SearXNG client reads its settings on every call rather than caching them, so changing the
   * instance URL in settings takes effect on the next search with no restart.
   */
  @Provides
  @Singleton
  fun provideSearxngClient(dataStoreRepository: DataStoreRepository): SearxngClient =
    SearxngClient(readSettings = { dataStoreRepository.readWebSearchSettings() })

  /** Reads its settings on every call, so a changed address takes effect without a restart. */
  @Provides
  @Singleton
  fun provideHomeAssistantClient(dataStoreRepository: DataStoreRepository): HomeAssistantClient =
    HomeAssistantClient(readSettings = { dataStoreRepository.readSmartHomeSettings() })

  @Provides
  @Singleton
  @AssistantAgentTools
  fun provideAssistantTools(
    skillManager: SkillManager,
    searxngClient: SearxngClient,
    homeAssistantClient: HomeAssistantClient,
    dataStoreRepository: DataStoreRepository,
    memoryVaultRepository: MemoryVaultRepository,
  ): AgentTools =
    AssistantTools(
        searxngClient = searxngClient,
        homeAssistantClient = homeAssistantClient,
        settingsRepository = dataStoreRepository,
        memoryVault = memoryVaultRepository,
      )
      .apply { skillsProvider = skillManager }

  @Provides
  @Singleton
  @AssistantExecutor
  fun provideAssistantExecutor(
    skillManager: SkillManager,
    @AssistantAgentTools agentTools: AgentTools,
    llmSessionManager: LlmSessionManager,
  ): AgentRuntimeExecutor =
    DefaultAgentRuntimeExecutor(
      skillsProvider = skillManager,
      toolsProvider = agentTools,
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
    )

  @Provides
  @IntoSet
  fun provideAssistantTask(
    @ApplicationContext context: Context,
    skillManager: SkillManager,
    @AssistantAgentTools agentTools: AgentTools,
    @AssistantExecutor executor: AgentRuntimeExecutor,
    dataStoreRepository: DataStoreRepository,
    searxngClient: SearxngClient,
    homeAssistantClient: HomeAssistantClient,
    memoryVaultRepository: MemoryVaultRepository,
  ): CustomTask =
    AssistantTask(
      context = context,
      skillsProvider = skillManager,
      agentTools = agentTools,
      executor = executor,
      dataStoreRepository = dataStoreRepository,
      searxngClient = searxngClient,
      homeAssistantClient = homeAssistantClient,
      memoryVault = memoryVaultRepository,
    )
}
