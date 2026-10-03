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

package com.google.ai.edge.gallery.media

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log

private const val TAG = "AGMusicApps"

/** A music app on this phone that can be asked to play something. */
data class MusicApp(val packageName: String, val label: String)

/**
 * Which app "play something" goes to.
 *
 * Android has no "default music app" for a third-party app to read - the setting people think of
 * does not exist. What does exist is a search-and-play intent, and several apps that answer it, so
 * asking Android to play Radiohead opens a chooser every single time. That is fine once and
 * unbearable as the way an assistant works.
 *
 * So the choice is made here instead, in the app's own settings, and remembered. It is not a system
 * default and does not pretend to be one: it is which app this assistant sends music to.
 */
object MusicApps {

  /**
   * The apps that answer a request to play something by name.
   *
   * Uses the search-and-play intent rather than a hardcoded list of music services, so a player
   * nobody has heard of is treated exactly like the famous ones.
   */
  fun installed(context: Context): List<MusicApp> {
    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
    return try {
      context.packageManager
        .queryIntentActivities(intent, 0)
        .mapNotNull { resolved ->
          val packageName = resolved.activityInfo?.packageName ?: return@mapNotNull null
          MusicApp(
            packageName = packageName,
            label = resolved.loadLabel(context.packageManager).toString(),
          )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
    } catch (e: Exception) {
      Log.w(TAG, "Could not list music apps", e)
      emptyList()
    }
  }

  /**
   * Something that plays [query], aimed at [packageName] when one is chosen and installed.
   *
   * Returns null when nothing on the phone can answer at all, which is a different answer from
   * "the chosen app is gone" - that one silently falls back to whatever else can play, because a
   * remembered app that has since been uninstalled should not stop music working.
   */
  fun playIntent(context: Context, query: String, packageName: String): Intent? {
    val intent =
      Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        putExtra(android.app.SearchManager.QUERY, query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    if (packageName.isNotBlank()) {
      val aimed = Intent(intent).setPackage(packageName)
      if (aimed.resolveActivity(context.packageManager) != null) return aimed
      Log.d(TAG, "The chosen music app is no longer installed; letting the phone choose")
    }
    return if (intent.resolveActivity(context.packageManager) != null) intent else null
  }

  /** The readable name of [packageName], or the package itself when it cannot be resolved. */
  fun labelOf(context: Context, packageName: String): String =
    installed(context).firstOrNull { it.packageName == packageName }?.label ?: packageName
}
