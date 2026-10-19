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
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.engine.mcp.RemoteTool
import com.lin.agent.engine.mcp.qualifiedName

/**
 * A tool on somebody else's server, offered to the model as one of Lin's.
 *
 * Three things are worth noticing about how little this does.
 *
 * **The schema is the server's.** [parametersSchema] hands the server's own JSON Schema
 * through unchanged rather than flattening it into [ToolParameter] and building it back up,
 * which would lose nesting, enums and defaults the server meant the model to see. That
 * override is the reason `AgentTool` has one.
 *
 * **The name is prefixed.** Tool names are only unique within one server, and two servers each
 * offering `search` is the collision the specification itself gives as the example. Without a
 * prefix the second one loaded would simply disappear.
 *
 * **A tool execution error is a result, not a failure.** When the server runs the tool and it
 * does not work, that is something the model can act on - a date in the wrong format, a value
 * out of range - so it comes back as a failed [ToolResult] carrying the server's own words. A
 * connection problem is different and says so, because no amount of the model retrying will
 * fix a server that is not there.
 */
class McpTool(
  val serverName: String,
  private val remote: RemoteTool,
  private val client: McpClient,
) : AgentTool {

  override val name = qualifiedName(serverName, remote.name)

  override val description =
    remote.description.ifBlank { "A tool on the $serverName server." }.let { said ->
      // Said in the description rather than left for the user to work out from the prefix:
      // the model is choosing between this and Lin's own tools, and "this one goes over the
      // network to a server you added" is the difference that matters.
      if (said.endsWith(".")) "$said From $serverName." else "$said. From $serverName."
    }

  /**
   * Empty, and unused.
   *
   * Lin's own tools describe their arguments as a list and the schema is generated from it.
   * This one has a schema already, so the list has nothing to say - see [parametersSchema].
   */
  override val parameters = emptyList<ToolParameter>()

  override val parametersSchema = remote.inputSchema

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult =
    try {
      // The server's own name for it, not the prefixed one Lin shows the model.
      val outcome = client.call(remote.name, arguments)
      if (outcome.failed) ToolResult.Failed(outcome.text) else ToolResult.Ok(outcome.text)
    } catch (stopped: kotlinx.coroutines.CancellationException) {
      throw stopped
    } catch (failure: Throwable) {
      Log.w(TAG, "$name failed", failure)
      ToolResult.Failed(
        "The $serverName server could not be reached: ${failure.message ?: "no answer"}. " +
          "That is a connection problem rather than something to try differently, so say so " +
          "and carry on without it."
      )
    }

  private companion object {
    const val TAG = "LinMcpTool"
  }
}
