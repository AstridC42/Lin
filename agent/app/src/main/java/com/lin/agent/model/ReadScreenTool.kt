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
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.screen.LinAccessibility
import com.lin.agent.screen.accessibilityGranted

/**
 * What is on the screen behind the panel.
 *
 * The other half of what screen access is worth, and the cheaper half: "what does this say",
 * "read me that", "what's the code in this message" - all of which are ordinary things to ask
 * an assistant and none of which Lin could answer about anything outside itself.
 *
 * Read when asked and not kept. Nothing here writes to disk, and the accessibility service
 * behind it collects no events at all - see [LinAccessibility].
 *
 * Deliberately only the text, in order, with the duplicates removed. The whole node tree is
 * mostly layout containers with no words in them, and sending those to a 2B model fills its
 * context with nothing.
 */
class ReadScreenTool(private val context: Context) : AgentTool {

  override val name = "read_screen"

  override val description =
    "Read the text on the screen behind Lin - the app the user was looking at. Use it when " +
      "they ask about something on their screen."

  override val parameters = emptyList<ToolParameter>()

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    if (!accessibilityGranted(context)) {
      return ToolResult.Failed(
        "Lin cannot see other apps' screens. That needs Lin's screen access, which is granted " +
          "by hand in the phone's accessibility settings, under Lin. Say so plainly."
      )
    }
    val service =
      LinAccessibility.live
        ?: return ToolResult.Failed("Lin's screen access is on but not running yet.")

    val nodes = service.nodes()
    if (nodes.isEmpty()) {
      return ToolResult.Failed(
        "There is nothing readable on the screen behind Lin. It may be a video, a game, or an " +
          "app that marks its window as secure - a banking app will."
      )
    }

    // Text first, then labels for the things with no text - a button that says nothing but is
    // described as "Send" is part of what is on screen.
    val said =
      nodes
        .map { it.text.ifBlank { it.description }.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MOST_LINES)

    if (said.isEmpty()) {
      return ToolResult.Failed("The screen behind Lin has no text on it.")
    }

    val app = service.frontApp().ifBlank { "the app in front" }
    return ToolResult.Ok(
      buildString {
        append("On screen in ").append(app).append(":")
        said.forEach { append("\n").append(it) }
      }
    )
  }

  private companion object {
    /** Enough for a screenful of a messaging app, short of filling a small model's context. */
    const val MOST_LINES = 120
  }
}
