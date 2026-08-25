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

import com.google.ai.edge.gallery.customtasks.agentchat.AgentToolsImpl
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.tools.DeviceActionsTool
import com.google.ai.edge.gallery.tools.MediaControlTool
import com.google.ai.edge.gallery.tools.NotificationsTool
import com.google.ai.edge.gallery.tools.RemindersTool
import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.gallery.tools.WebSearchTool

/**
 * The tool set of the unified assistant.
 *
 * It is everything the upstream agent chat has - load_skill, runMcpTool, the JS skill runtime and
 * run_intent - plus the two things a Google Assistant replacement needs and Edge Gallery does not
 * ship: live web search against the user's own SearXNG instance, and device actions such as
 * placing a call.
 *
 * The web search tools are only offered to the model when an instance is configured and enabled,
 * so a model on a device without web search is never told about a tool that would always fail.
 */
class AssistantTools(
  private val searxngClient: SearxngClient,
  private val settingsRepository: DataStoreRepository,
) : AgentToolsImpl() {

  private val webSearchTool by lazy { WebSearchTool(searxngClient = searxngClient) }

  private val deviceActionsTool by lazy {
    DeviceActionsTool(
      context = context,
      allowDirectCalls = { settingsRepository.readAssistantSettings().allowDirectCalls },
    )
  }

  private val notificationsTool by lazy { NotificationsTool(context = context) }

  private val mediaControlTool by lazy { MediaControlTool(context = context) }

  private val remindersTool by lazy {
    RemindersTool(context = context, repository = settingsRepository)
  }

  override fun getAvailableTools(): List<ToolDefinition> {
    val tools = mutableListOf<ToolDefinition>()
    tools.addAll(super.getAvailableTools())
    tools.add(deviceActionsTool)
    // Offered whether or not notification access has been granted, unlike web search. A tool that
    // is absent leaves the model saying it cannot read notifications at all; one that is present
    // and reports the missing permission lets it tell the user how to fix it.
    tools.add(notificationsTool)
    tools.add(mediaControlTool)
    tools.add(remindersTool)
    if (searxngClient.isConfigured()) {
      tools.add(webSearchTool)
    }
    return tools
  }
}
