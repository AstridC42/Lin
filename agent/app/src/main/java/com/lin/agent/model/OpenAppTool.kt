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
import android.content.Intent
import android.content.pm.PackageManager
import com.lin.agent.core.apps.InstalledApp
import com.lin.agent.core.apps.appNamed
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult

/**
 * Opens an app by the name somebody said.
 *
 * The matching is in [appNamed] and tested there, because this is where being almost right is
 * worst: opening Facebook Messenger for "messages" is not a near miss, it is the wrong app in
 * front of somebody who asked for a different one. Nothing is opened on a guess - an app that
 * is not installed is said rather than approximated.
 *
 * Which apps exist is read through a `<queries>` declaration for launchable apps rather than
 * QUERY_ALL_PACKAGES. It is the narrower grant and it is also the right one: Lin only ever
 * wants apps a person could have opened from their home screen.
 */
class OpenAppTool(private val context: Context, private val guard: LaunchGuard) : AgentTool {

  override val name = "open_app"

  override val description = "Open an app on the phone by name."

  override val parameters =
    listOf(
      ToolParameter(
        name = "app",
        type = ParamType.STRING,
        description = "The app's name as the user said it, for example tidal, or maps.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val said = (arguments["app"] as? String).orEmpty().trim()
    if (said.isEmpty()) return ToolResult.Failed("No app was named.")

    val installed = installedApps()
    if (installed.isEmpty()) {
      return ToolResult.Failed("Lin cannot see which apps are installed on this phone.")
    }

    val app =
      appNamed(said, installed)
        ?: return ToolResult.Failed(
          "There is no app called '$said' on this phone. Say that rather than opening " +
            "something else."
        )

    // Guarded like the other things that put a screen in front of somebody: a result the model
    // misreads should not be able to launch the same app three times.
    if (!guard.allow("open-${app.packageName}", System.currentTimeMillis())) {
      return ToolResult.Ok("Done - ${app.label} is already opening.")
    }

    val intent =
      context.packageManager.getLaunchIntentForPackage(app.packageName)
        ?: return ToolResult.Failed("${app.label} is installed but has nothing to open.")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return runCatching {
        context.startActivity(intent)
        ToolResult.Ok("Done - ${app.label} is open.")
      }
      .getOrElse { ToolResult.Failed("${app.label} would not open.") }
  }

  private fun installedApps(): List<InstalledApp> = installedApps(context)
}
