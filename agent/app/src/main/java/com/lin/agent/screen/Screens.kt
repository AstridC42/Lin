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

package com.lin.agent.screen

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lin.agent.core.screen.isEnabledService

/**
 * Whether the user has turned Lin's accessibility service on.
 *
 * Read from the phone's own list rather than from [LinAccessibility.live], and the difference
 * matters: the static reference is null for the moment between the permission being granted
 * and the system binding the service, and null again if the process was restarted while the
 * permission stayed on. A settings screen that reported "off" in either case would have
 * somebody toggling a switch that was already on.
 *
 * [LinAccessibility.live] is still what the *tools* check, because they need the thing itself
 * and not the permission to have it.
 */
fun accessibilityGranted(context: Context): Boolean =
  runCatching {
      isEnabledService(
        Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ),
        context.packageName,
        LinAccessibility::class.java.name,
      )
    }
    .getOrDefault(false)

/**
 * Opens the phone's accessibility settings.
 *
 * There is no way to request this permission: it is granted by hand, behind a warning screen
 * that says what the service will be able to do, and that is correct for what it grants. The
 * most an app may do is open the list.
 */
fun openAccessibilitySettings(context: Context): Boolean =
  runCatching {
      context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      true
    }
    .getOrDefault(false)
