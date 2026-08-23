/*
 * Copyright 2026 The Star Storyteller Authors
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

import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AssistantExecutor
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Chat view model of the unified assistant.
 *
 * Identical in behaviour to [com.google.ai.edge.gallery.customtasks.agentchat.AgentChatViewModel],
 * but bound to [AssistantExecutor] so the assistant conversation has its own LLM session and its
 * own tool set.
 */
@HiltViewModel
class AssistantViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  chatSessionRepository: ChatSessionRepository,
  @AssistantExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
) :
  LlmChatViewModel(
    systemPromptRepository,
    chatSessionRepository,
    runtimeExecutor,
    llmSessionManager,
  )
