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

package com.lin.agent.mcp

import android.util.Log
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.mcp.McpServer
import com.lin.agent.engine.mcp.serversFrom
import com.lin.agent.engine.mcp.storedFrom
import com.lin.agent.model.Tools
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** What a server is doing, for the settings screen. */
data class ServerStatus(
  val server: McpServer,
  val toolCount: Int = 0,
  val trouble: String = "",
  val checking: Boolean = false,
)

/**
 * The MCP servers the user has added, and the tools they provide.
 *
 * Kept apart from [Tools] so the dependency runs one way: this reads the servers, builds the
 * tools, and hands them over. [Tools] knows nothing about MCP beyond holding whatever it is
 * given, which is what lets the whole feature be absent without the tool list caring.
 *
 * Refreshed rather than subscribed. A server's tool list can change and the protocol has a
 * notification for it, but receiving that means holding a long-lived stream open to every
 * server for the life of the app - on a phone, on mobile data. Reading the list when the app
 * starts and when the user asks is the proportionate version.
 */
@Singleton
class McpConnections
@Inject
constructor(private val settings: SettingsRepository, private val tools: Tools) {

  private val clients = mutableMapOf<String, McpClient>()

  private val _status = MutableStateFlow<List<ServerStatus>>(emptyList())
  val status: StateFlow<List<ServerStatus>> = _status.asStateFlow()

  /** The servers as stored, without talking to any of them. */
  suspend fun servers(): List<McpServer> =
    serversFrom(runCatching { settings.mcpServers.first() }.getOrNull())

  /**
   * Asks every server what it offers, and rebuilds the tool list.
   *
   * All of them, every time, rather than the one that changed: a server that has been removed
   * must take its tools with it, and the only way to know which those were is to rebuild from
   * what the servers currently say.
   *
   * One server being unreachable does not cost the others their tools. That is the whole
   * reason each is caught separately - a laptop that is asleep should not take a working
   * remote server's tools off the list with it.
   */
  suspend fun refresh() {
    val configured = servers()
    _status.value = configured.map { ServerStatus(it, checking = true) }

    val collected = mutableListOf<AgentTool>()
    val reported = mutableListOf<ServerStatus>()

    for (server in configured) {
      val client = clients.getOrPut(server.url) { McpClient(server) }
      val outcome = runCatching { client.tools() }
      outcome
        .onSuccess { remote ->
          remote.forEach { collected.add(McpTool(server.name, it, client)) }
          reported.add(ServerStatus(server, toolCount = remote.size))
        }
        .onFailure { failure ->
          Log.w(TAG, "${server.url} would not list its tools", failure)
          reported.add(
            ServerStatus(server, trouble = failure.message ?: failure::class.simpleName.orEmpty())
          )
        }
    }

    // Clients for servers that are gone are dropped, so a removed server stops holding a
    // cached era and session for a URL nobody is using.
    clients.keys.retainAll(configured.map { it.url }.toSet())

    tools.setRemote(collected)
    _status.value = reported
  }

  suspend fun add(server: McpServer) {
    val next = servers().filterNot { it.url == server.url } + server
    settings.setMcpServers(storedFrom(next))
    refresh()
  }

  suspend fun remove(url: String) {
    settings.setMcpServers(storedFrom(servers().filterNot { it.url == url }))
    clients.remove(url)
    refresh()
  }

  private companion object {
    const val TAG = "LinMcpConnections"
  }
}
