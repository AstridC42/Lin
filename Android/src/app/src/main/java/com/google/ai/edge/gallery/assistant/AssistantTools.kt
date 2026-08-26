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
import com.google.ai.edge.gallery.smarthome.HomeAssistantClient
import com.google.ai.edge.gallery.tools.CallJsSkillResultImage
import com.google.ai.edge.gallery.tools.CallJsSkillResultWebview
import com.google.ai.edge.gallery.tools.DeviceActionsTool
import com.google.ai.edge.gallery.tools.MediaControlTool
import com.google.ai.edge.gallery.tools.NotificationsTool
import com.google.ai.edge.gallery.tools.RemindersTool
import com.google.ai.edge.gallery.tools.SmartHomeTool
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
  private val homeAssistantClient: HomeAssistantClient,
  private val settingsRepository: DataStoreRepository,
) : AgentToolsImpl() {

  private val webSearchTool by lazy {
    WebSearchTool(
      searxngClient = searxngClient,
      showImageInChat = { base64 -> resultImageToShow = CallJsSkillResultImage(base64 = base64) },
      showPageInChat = { url ->
        resultWebviewToShow =
          CallJsSkillResultWebview(url = url, iframe = false, aspectRatio = DEFAULT_PAGE_ASPECT)
      },
    )
  }

  private val deviceActionsTool by lazy {
    DeviceActionsTool(
      context = context,
      allowDirectCalls = { settingsRepository.readAssistantSettings().allowDirectCalls },
    )
  }

  private val notificationsTool by lazy { NotificationsTool(context = context) }

  private val mediaControlTool by lazy { MediaControlTool(context = context) }

  private val smartHomeTool by lazy { SmartHomeTool(client = homeAssistantClient) }

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
    // Only when an instance is set up, for the same reason as web search: a model told about a
    // tool that can only fail will keep reaching for it and reporting the failure as a result.
    if (homeAssistantClient.isConfigured()) {
      tools.add(smartHomeTool)
    }
    return tools
  }

  private companion object {
    /** Taller than wide: a page read inside a chat bubble is mostly text running downwards. */
    const val DEFAULT_PAGE_ASPECT = 0.75f
  }
}
