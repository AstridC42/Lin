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

package com.google.ai.edge.gallery.car

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * What can be said about Android Auto from inside the app.
 *
 * Which is less than anyone would like, and saying so is the point of this file. Whether a
 * sideloaded app appears on the car screen is decided entirely by Android Auto, in another process,
 * from settings this app cannot read and cannot change: developer mode has to be turned on there,
 * and "Unknown sources" enabled, before an app installed from anywhere but the Play Store is
 * offered at all. There is no API that reports the state of either, and no error when they are off
 * - the app is simply not in the list.
 *
 * So the honest thing is to check the parts that can be checked, put the rest in writing where
 * someone can follow it, and give them one tap to the settings screen it happens on.
 */
object AndroidAutoStatus {

  /** Android Auto's package. Declared under `<queries>` so it stays visible on Android 11+. */
  private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

  /** Whether Android Auto is on this phone at all. On many phones it is part of Play Services. */
  fun isInstalled(context: Context): Boolean =
    try {
      context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO) != null ||
        context.packageManager.getPackageInfo(ANDROID_AUTO, 0) != null
    } catch (e: Exception) {
      false
    }

  /**
   * Something that opens Android Auto's own settings, or null when nothing will.
   *
   * Its launcher entry is hidden on most phones, so the app info page is the reliable way in - from
   * there, "Open" reaches the settings the steps talk about.
   */
  fun settingsIntent(context: Context): Intent? {
    val launch = context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
    if (launch != null) return launch
    if (!isInstalled(context)) return null
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:$ANDROID_AUTO")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }
}
