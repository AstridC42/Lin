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

package com.lin.agent.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lin.agent.core.voice.AssistantRole

/**
 * Whether this phone's assistant is Lin.
 *
 * Three answers, not two. Null means the phone would not say - the role service is not
 * something an app is promised - and "no" would be a guess dressed up as a fact on the one
 * screen where somebody is trying to find out why the power button does nothing.
 *
 * Worth reading on every return to the settings screen rather than once: the only way to change
 * this is to leave for the system's own settings and come back, so a value read at launch is
 * stale exactly when it matters.
 */
fun linIsTheAssistant(context: Context): Boolean? =
  runCatching {
      context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
    .getOrNull()

/**
 * Opens the screen where the assistant is chosen, and says whether it got there.
 *
 * Nothing here can set the role - see [AssistantRole]. The return value exists so the caller
 * can say something true when no screen could be opened, instead of leaving somebody looking
 * at a button that did nothing.
 */
fun openAssistantSettings(context: Context): Boolean {
  val action =
    AssistantRole.firstResolvable { candidate ->
      // Asked of the package manager rather than assumed from the version: which of these
      // screens exists is a manufacturer's decision.
      context.packageManager.resolveActivity(Intent(candidate), 0) != null
    } ?: return false

  return runCatching {
      context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    }
    .getOrDefault(false)
}

/**
 * Whether Lin may open a window while it is in the background.
 *
 * Named for what it is for rather than for the permission behind it. Lin draws no overlays;
 * this is the only exemption that lets a background app start an activity at all, and without
 * it the wake word has heard a question it cannot answer - see
 * [com.lin.agent.core.voice.wakeRoute].
 */
fun canAppearOverOtherApps(context: Context): Boolean =
  runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

/**
 * Opens the screen where that permission is granted.
 *
 * With the package in the URI, so it lands on Lin's own row rather than a list to find it in.
 * Falling back to the list if the phone will not take the URI, which is better than a button
 * that throws.
 */
fun openAppearOverOtherApps(context: Context) {
  val screen = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
  val mine =
    Intent(screen, Uri.fromParts("package", context.packageName, null))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(mine) }
    .onFailure {
      runCatching { context.startActivity(Intent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Whether the phone will summon the panel, or something else of ours.
 *
 * Android stores the assistant as a component name, so a phone told "Lin" before the panel
 * existed still points at the whole app by name - which is why the power button kept opening
 * the old screen after the update. [com.lin.agent.MainActivity] hands that summons on, so it
 * works either way; this is for saying why it goes the long way round, and that re-picking Lin
 * fixes it.
 *
 * Null when the phone will not say what it stored. Unknown is not "wrong", and a warning shown
 * on a guess is worse than no warning.
 */
fun assistantSummonsThePanel(context: Context): Boolean? {
  val stored =
    runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }
      .getOrNull() ?: return null
  if (stored.isBlank()) return null
  return AssistantRole.pointsAt(
    stored = stored,
    packageName = context.packageName,
    className = "com.lin.agent.AssistActivity",
  )
}
