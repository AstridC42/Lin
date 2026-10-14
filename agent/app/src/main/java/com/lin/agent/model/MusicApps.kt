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
import android.provider.MediaStore
import com.lin.agent.core.apps.InstalledApp
import com.lin.agent.core.media.KNOWN_MUSIC_APPS

/**
 * The apps on this phone that could play music.
 *
 * Asked two ways, because an app may answer either and plenty answer only one. A media browser
 * service is what a car or a watch looks for and almost every streaming app publishes one; the
 * play-from-search intent is what Lin asks with, and some apps declare that without a browser.
 * The union is offered, and anything Lin knows the name of is listed first - a list with
 * Tidal and Spotify at the top and six system sound players under them is easier to pick from
 * than one in package order.
 *
 * Lin itself is dropped. It holds a media session of its own while speaking, and offering
 * "play music in Lin" would be a loop.
 */
fun installedMusicApps(context: Context): List<InstalledApp> {
  val manager = context.packageManager
  val found = LinkedHashMap<String, InstalledApp>()

  runCatching {
    manager
      .queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
      .forEach { service ->
        val name = service.serviceInfo.packageName
        found.getOrPut(name) { InstalledApp(appLabel(context, name), name) }
      }
  }

  runCatching {
    manager
      .queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
      .forEach { activity ->
        val name = activity.activityInfo.packageName
        found.getOrPut(name) { InstalledApp(appLabel(context, name), name) }
      }
  }

  return found.values
    .filter { it.packageName != context.packageName }
    .sortedWith(
      compareBy({ if (KNOWN_MUSIC_APPS.containsKey(it.packageName)) 0 else 1 }, { it.label })
    )
}
