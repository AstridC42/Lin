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

import android.content.Context
import com.lin.agent.core.calendar.Calendars
import com.lin.agent.core.documents.DocumentLibrary
import com.lin.agent.core.documents.Documents
import com.lin.agent.core.documents.PdfPages
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ToolRegistry
import com.lin.agent.notifications.NotificationRelay
import com.lin.agent.privileged.Privileged
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The last thing the person typed or said, for the one decision that must not be the model's. */
@Singleton
class LastSaid @Inject constructor() {
  @Volatile var text: String = ""
}

/** A tool as the settings screen needs to show it. */
data class ToolListing(val name: String, val description: String, val fromServer: String = "")

/**
 * Every tool Lin has, in one place that both the conversation and the settings screen read.
 *
 * It used to be a `ToolRegistry` built inside the conversation's ViewModel, which was fine
 * while nothing else needed to know what tools existed. Two things changed that: a screen for
 * switching tools off has to list them, and tools from an MCP server arrive while the app is
 * running rather than at construction. A list built in one ViewModel can serve neither.
 *
 * So the registry lives here and is a [StateFlow]: the built-in tools are fixed, remote ones
 * are added when servers are read, and whoever is holding a registry gets the new one. The
 * conversation takes the current value at the start of each turn, so a server appearing
 * mid-answer cannot change the tools half way through it.
 */
@Singleton
class Tools
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val settings: SettingsRepository,
  private val backends: ChatBackends,
  private val phoneTool: PhoneTool,
  private val memories: MemoryStore,
  private val embedders: Embedders,
  private val relay: NotificationRelay,
  private val calendars: Calendars,
  private val documentLibrary: DocumentLibrary,
  private val documents: Documents,
  private val pdfPages: PdfPages,
  private val attachments: Attachments,
  private val launchGuard: LaunchGuard,
  private val privileged: Privileged,
  private val lastSaid: LastSaid,
  private val web: com.lin.agent.web.Web,
) {

  /**
   * The tools that are part of the app.
   *
   * Built once. None of them depends on anything that changes at runtime - the ones whose
   * availability does change, like `delegate` with no server, are withheld per turn rather
   * than removed from here, so that the settings screen can still show them and say why.
   */
  private val builtIn: List<AgentTool> =
    listOf(
      DelegateTool { backends.serverSubAgent() },
      phoneTool,
      RememberTool(memories, embedders),
      RecallTool(memories, embedders),
      FindPhotoTool(memories, embedders, attachments, context),
      UnreadTool(relay),
      ReplyTool(relay),
      CalendarTool(calendars),
      MediaTool(context),
      WeatherTool(context),
      PlayMusicTool(context, settings, launchGuard),
      PhoneSettingTool(context, launchGuard, privileged),
      SendMessageTool(context, settings, launchGuard, lastSaid = { lastSaid.text }),
      ReadScreenTool(context),
      // The web. The single largest thing Lin could not do: an assistant with a calendar, a
      // torch and no way to find out anything it was not trained on is a toy.
      SearchWebTool(settings, web),
      ReadPageTool(web),
      ContactTool(context),
      NavigateTool(context, launchGuard),
      DeviceStatusTool(context),
      OpenAppTool(context, launchGuard),
      AddToCalendarTool(context, launchGuard),
      AlarmTool(context, launchGuard),
      ListDocumentsTool(documentLibrary),
      ReadDocumentTool(documentLibrary, documents),
      WriteDocumentTool(documentLibrary, documents, pdfPages),
    )

  private val _registry = MutableStateFlow(ToolRegistry(builtIn))

  /** The registry as it is now. A turn takes one value and keeps it for the whole turn. */
  val registry: StateFlow<ToolRegistry> = _registry.asStateFlow()

  /** Remote tools currently in the registry, by the server they came from. */
  @Volatile private var remote: List<AgentTool> = emptyList()

  /**
   * Replaces the remote tools with [tools].
   *
   * All of them at once rather than one server at a time: a server that has gone away should
   * take its tools with it, and the only way to know which those were is to rebuild the lot
   * from what the servers currently say.
   *
   * A name that collides with a built-in tool loses. The built-in ones are what the prompt and
   * the rest of the app are written around, and a remote `weather` silently replacing Lin's
   * own would be the kind of fault nobody could see from the outside.
   */
  fun setRemote(tools: List<AgentTool>) {
    val taken = builtIn.map { it.name }.toSet()
    remote = tools.filterNot { it.name in taken }
    _registry.value = ToolRegistry(builtIn + remote)
  }

  /** Everything an orchestrator could be offered, for the screen that switches them off. */
  fun listing(): List<ToolListing> =
    _registry.value.forRole(AgentRole.ORCHESTRATOR).map { tool ->
      ToolListing(
        name = tool.name,
        description = tool.description,
        fromServer = (tool as? com.lin.agent.mcp.McpTool)?.serverName.orEmpty(),
      )
    }
}
