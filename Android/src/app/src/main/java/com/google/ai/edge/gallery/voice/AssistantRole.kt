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

package com.google.ai.edge.gallery.voice

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val TAG = "AGAssistantRole"

/**
 * Becoming the phone's assistant, and finding out whether you already are.
 *
 * Telling someone to "go to Settings, Apps, Default apps, Digital assistant app" is a poor answer:
 * the path differs between manufacturers, several of them bury or rename it, and if the app is not
 * listed there is nothing to see and no reason given. Android has a direct request for this - the
 * assistant is a *role*, and an app may ask for it and get a system dialog.
 */
object AssistantRole {

  /** Whether Lin is currently the device's digital assistant. */
  fun isHeld(context: Context): Boolean {
    // The role API knows for certain from Android 10 on. Below that, and on manufacturer builds
    // that do not implement the role, fall back to reading the setting the assistant is stored in.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val roleManager = context.getSystemService(RoleManager::class.java)
      if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
      }
    }
    return currentAssistantPackage(context) == context.packageName
  }

  /**
   * An intent that asks to become the assistant, or null when the device cannot be asked.
   *
   * Returns null rather than throwing when the role is unavailable, so a caller can offer the
   * settings screen instead of a button that does nothing.
   */
  fun requestIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
    if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
    if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
    return try {
      roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    } catch (e: Exception) {
      Log.w(TAG, "The assistant role cannot be requested on this device", e)
      null
    }
  }

  /**
   * The system screen where the assistant is chosen, for when the role cannot be requested.
   *
   * Tries the assistant screen itself first and falls back to the app's own settings page, since
   * not every build exposes the former.
   */
  fun settingsIntent(context: Context): Intent {
    val voiceInput = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    if (voiceInput.resolveActivity(context.packageManager) != null) return voiceInput
    return Intent(Settings.ACTION_SETTINGS)
  }

  /** The package currently set as the assistant, or empty when there is none. */
  private fun currentAssistantPackage(context: Context): String {
    val stored =
      Settings.Secure.getString(context.contentResolver, "assistant").orEmpty().ifEmpty {
        Settings.Secure.getString(context.contentResolver, "voice_interaction_service").orEmpty()
      }
    if (stored.isEmpty()) return ""
    return ComponentName.unflattenFromString(stored)?.packageName ?: ""
  }
}
