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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.lin.agent.core.apps.InstalledApp
import com.lin.agent.core.apps.AppRole
import com.lin.agent.core.apps.appNamed
import com.lin.agent.core.apps.appNamedExactly
import com.lin.agent.core.apps.appRoleFor
import com.lin.agent.core.apps.closestApps
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

  override val description =
    "Open an app on the phone: by its name, or by the kind of app - gallery, clock, camera, " +
      "settings, calendar, calculator, contacts, messages, email, maps, browser, music, files."

  override val parameters =
    listOf(
      ToolParameter(
        name = "app",
        type = ParamType.STRING,
        description = "The app's name as the user said it, for example tidal, or maps.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  // Said outright, because "open gallery" reads to a small model like a request about photos
  // and it reaches for find_photo instead - which searches pictures and opens nothing.
  override val guidance =
    "Whenever the user says to open, launch or start an app - including built-in ones like " +
      "the gallery, clock, camera, settings, calendar or files - call open_app with the name " +
      "as they said it. Do not use find_photo to open the gallery."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val said = (arguments["app"] as? String).orEmpty().trim()
    if (said.isEmpty()) return ToolResult.Failed("No app was named.")

    val installed = installedApps()

    // An app actually called what was said wins - somebody's own app named "Clock" is the one
    // they mean. Then the kind of app: "gallery", "clock", "camera" go to whichever app does
    // that job on this phone, by the standard request every Android phone answers, with no
    // label involved. Only then the looser name matching, which is where guesses live.
    appNamedExactly(said, installed)?.let { return launch(it) }
    appRoleFor(said)?.let { role -> openRole(role, said)?.let { return it } }
    appNamed(said, installed)?.let { return launch(it) }

    // Said with what Lin can actually see, so a failure can be acted on - and so a phone where
    // Lin can see almost nothing shows up as that, instead of as "there is no such app".
    val nearest = closestApps(said, installed)
    return ToolResult.Failed(
      "There is no app called '$said' that Lin can find. Lin can see ${installed.size} apps on " +
        "this phone" +
        (if (nearest.isNotEmpty()) "; the closest names are ${nearest.joinToString(", ")}" else "") +
        ". Tell the user which ones are closest rather than opening something else."
    )
  }

  private fun launch(app: InstalledApp): ToolResult {
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

  /**
   * The app that does [role] on this phone, asked for the standard way.
   *
   * No visibility check first: starting an implicit intent needs none, and Android picks the
   * user's own default - Samsung's gallery on a Samsung, Google Photos where that is the
   * default. Null when nothing on the phone answers, so the name matching gets its turn.
   */
  private fun openRole(role: AppRole, said: String): ToolResult? {
    if (!guard.allow("open-role-${role.name}", System.currentTimeMillis())) {
      return ToolResult.Ok("Done - the ${role.name.lowercase()} app is already opening.")
    }
    val intent =
      when (role) {
        AppRole.GALLERY -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        AppRole.CALENDAR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        AppRole.CALCULATOR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        AppRole.CONTACTS -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
        AppRole.MESSAGES -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        AppRole.EMAIL -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
        AppRole.MAPS -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
        AppRole.BROWSER -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        AppRole.MUSIC -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        AppRole.FILES -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES)
        AppRole.STORE -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MARKET)
        AppRole.CLOCK -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        AppRole.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        AppRole.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        AppRole.PHONE -> Intent(Intent.ACTION_DIAL)
      }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      ToolResult.Ok("Done - the $said app is open.")
    } catch (nothing: ActivityNotFoundException) {
      null
    } catch (refused: SecurityException) {
      null
    }
  }

  private fun installedApps(): List<InstalledApp> = installedApps(context)
}
