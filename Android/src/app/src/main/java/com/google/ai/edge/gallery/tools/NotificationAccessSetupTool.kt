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

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

/**
 * Stands in for [NotificationsTool] until notification access has been granted.
 *
 * Everything that tool offers - reading the shade, replying, dismissing, pressing a button on a
 * notification - is one permission away from being possible and worth nothing until it is granted.
 * Describing all five to the model in that state spends roughly two hundred tokens of a budget
 * measured in low thousands, on a phone where the same budget also has to hold the conversation.
 *
 * One tool says the same thing for all of them. It stays present rather than disappearing, for the
 * reason web search learned the hard way: a model with nothing resembling this among its tools
 * reports that there is nothing to read, which is indistinguishable from an empty shade and leaves
 * the user with no idea that a permission is what is missing.
 */
class NotificationAccessSetupTool : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Read the phone's notifications. Use for \"what did I miss\", \"any messages\", and before replying to or dismissing one."
  )
  fun readNotifications(
    @ToolParam(description = "Optional app name to filter by. Leave empty for everything.")
    fromApp: String = ""
  ): Map<String, Any> = notGranted()

  private fun notGranted(): Map<String, Any> =
    mapOf(
      "status" to "failed",
      "error" to
        "Notification access has not been granted, so nothing can be read, replied to or " +
          "dismissed. Tell the user to turn on Star Storyteller under Settings > Notifications > " +
          "Device & app notifications, or from the Guide panel in the app. Do not claim there are " +
          "no notifications.",
    )
}
