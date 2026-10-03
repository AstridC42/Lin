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

package com.google.ai.edge.gallery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.voice.EXTRA_START_LISTENING

/**
 * A home-screen widget: one tap to type, one tap to talk.
 *
 * The assist gesture only works once the app is the device's assistant, and on several manufacturer
 * builds it is awkward to reach even then. A widget works regardless of any of that, and is the
 * shortest route from a phone in the hand to a question.
 *
 * Two targets rather than one, because they are different intentions. Tapping the body opens the
 * chat to type; tapping the microphone opens it already listening. A single target would force one
 * of those on someone who wanted the other.
 */
class AssistantWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    for (id in appWidgetIds) {
      val views = RemoteViews(context.packageName, R.layout.widget_assistant)
      views.setOnClickPendingIntent(R.id.widget_root, openIntent(context, listen = false))
      views.setOnClickPendingIntent(R.id.widget_mic, openIntent(context, listen = true))
      appWidgetManager.updateAppWidget(id, views)
    }
  }

  private fun openIntent(context: Context, listen: Boolean): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action = Intent.ACTION_ASSIST
        if (listen) putExtra(EXTRA_START_LISTENING, true)
      }
    return PendingIntent.getActivity(
      context,
      // Distinct request codes, or the second target reuses the first one's intent and both taps
      // do the same thing - the extras of a reused PendingIntent are not updated.
      if (listen) 1 else 0,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
