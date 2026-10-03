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

import com.google.ai.edge.gallery.customtasks.agentchat.AgentToolsImpl
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.memory.MemoryVaultRepository
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.smarthome.HomeAssistantClient
import com.google.ai.edge.gallery.tools.CallJsSkillResultImage
import com.google.ai.edge.gallery.tools.CallJsSkillResultWebview
import com.google.ai.edge.gallery.tools.DeviceActionsTool
import com.google.ai.edge.gallery.tools.CalendarTool
import com.google.ai.edge.gallery.tools.MediaControlTool
import com.google.ai.edge.gallery.tools.MemoryTool
import com.google.ai.edge.gallery.notifications.LinNotificationListener
import com.google.ai.edge.gallery.tools.NotificationAccessSetupTool
import com.google.ai.edge.gallery.tools.NotificationsTool
import com.google.ai.edge.gallery.tools.RemindersTool
import com.google.ai.edge.gallery.tools.SmartHomeTool
import com.google.ai.edge.gallery.tools.ToolCallGuard
import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.gallery.tools.WeatherTool
import com.google.ai.edge.gallery.tools.WebSearchSetupTool
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
  private val memoryVault: MemoryVaultRepository,
) : AgentToolsImpl() {

  /** Shared by the web tools, so "the same call three times" is counted across all of them. */
  private val toolCallGuard = ToolCallGuard()

  private val webSearchTool by lazy {
    WebSearchTool(
      guard = toolCallGuard,
      searxngClient = searxngClient,
      showImageInChat = { base64 -> resultImageToShow = CallJsSkillResultImage(base64 = base64) },
      showPageInChat = { url ->
        resultWebviewToShow =
          CallJsSkillResultWebview(url = url, iframe = false, aspectRatio = DEFAULT_PAGE_ASPECT)
      },
    )
  }

  private val webSearchSetupTool by lazy { WebSearchSetupTool() }

  private val deviceActionsTool by lazy {
    DeviceActionsTool(
      context = context,
      allowDirectCalls = { settingsRepository.readAssistantSettings().allowDirectCalls },
    )
  }

  private val notificationsTool by lazy { NotificationsTool(context = context) }

  private val notificationSetupTool by lazy { NotificationAccessSetupTool() }

  private val mediaControlTool by lazy {
    MediaControlTool(
      context = context,
      preferredMusicApp = { settingsRepository.readAssistantSettings().preferredMusicApp },
    )
  }

  private val calendarTool by lazy { CalendarTool(context = context) }

  private val memoryTool by lazy { MemoryTool(context = context, vault = memoryVault) }

  private val smartHomeTool by lazy { SmartHomeTool(client = homeAssistantClient) }

  private val weatherTool by lazy { WeatherTool(context = context) }

  private val remindersTool by lazy {
    RemindersTool(context = context, repository = settingsRepository)
  }

  override fun getAvailableTools(): List<ToolDefinition> {
    val tools = mutableListOf<ToolDefinition>()
    tools.addAll(super.getAvailableTools())
    tools.add(deviceActionsTool)
    // Present whether or not notification access has been granted: a tool that is absent leaves
    // the model saying there are no notifications, which is indistinguishable from an empty shade.
    // Until it is granted, though, one tool says that as well as five do, and the four it replaces
    // cost tokens the conversation needs.
    if (LinNotificationListener.isGranted(context)) {
      tools.add(notificationsTool)
    } else {
      tools.add(notificationSetupTool)
    }
    tools.add(mediaControlTool)
    // Always offered, whether or not the calendar permission has been granted: the tools report
    // the missing permission themselves, and a calendar that is simply absent from the tool list
    // comes back to the user as "I cannot do that" with nothing to act on.
    tools.add(calendarTool)
    tools.add(memoryTool)
    tools.add(remindersTool)
    // Needs nothing configured, unlike web search - which is the point of it being here. Before
    // this, the most-asked question an assistant gets could only be answered on a phone whose
    // owner had set up a self-hosted metasearch instance.
    tools.add(weatherTool)
    // One or the other, never both - they answer to the same name. The stand-in exists so that
    // "search the web" reaches something that can explain the missing address, rather than falling
    // through to an answer from memory that looks like a search.
    if (searxngClient.isConfigured()) {
      tools.add(webSearchTool)
    } else {
      tools.add(webSearchSetupTool)
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
