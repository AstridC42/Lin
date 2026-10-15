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

/**
 * Every app with an icon, which is the only list of apps Lin ever asks for.
 *
 * Read through the `<queries>` declaration for launchable activities rather than with
 * QUERY_ALL_PACKAGES: that permission is for app stores and antivirus, and this question -
 * which apps could the person have opened themselves - is exactly what Lin needs and nothing
 * more.
 *
 * Shared because two tools need it and they must agree. "Open WhatsApp" and "message Dad on
 * WhatsApp" resolving against two slightly different lists is the kind of difference nobody
 * can see from the outside.
 */
fun installedApps(context: Context): List<InstalledApp> =
  runCatching {
      val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      context.packageManager
        .queryIntentActivities(launchable, PackageManager.MATCH_DEFAULT_ONLY)
        .map { resolved ->
          InstalledApp(
            label = resolved.loadLabel(context.packageManager).toString(),
            packageName = resolved.activityInfo.packageName,
          )
        }
        // One entry per app: a few apps publish several launcher icons, and a duplicate
        // label makes the shortest-label tie-break meaningless.
        .distinctBy { it.packageName }
    }
    .getOrDefault(emptyList())
