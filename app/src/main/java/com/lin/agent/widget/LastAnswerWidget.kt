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

package com.lin.agent.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lin.agent.R
import com.lin.agent.core.car.forTheCar
import com.lin.agent.history.ChatHistory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Lin's latest answer on the home screen, with the question it answered. Tap it to carry on in
 * the app, or the microphone to ask something else. Updated whenever the history changes.
 */
class LastAnswerWidget : GlanceAppWidget() {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun history(): ChatHistory
  }

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val history = EntryPointAccessors.fromApplication(context, Deps::class.java).history()
    history.ready()
    val latest = history.chats.value.firstOrNull()
    val question = latest?.lines?.lastOrNull { it.fromUser }?.text.orEmpty()
    val answer = latest?.lines?.lastOrNull { !it.fromUser }?.text?.let { forTheCar(it, longest = 280) }.orEmpty()
    provideContent { GlanceTheme { Content(question, answer) } }
  }

  @Composable
  private fun Content(question: String, answer: String) {
    val context = LocalContext.current
    Column(
      modifier =
        GlanceModifier.fillMaxSize()
          .background(GlanceTheme.colors.widgetBackground)
          .cornerRadius(24.dp)
          .padding(14.dp)
          .clickable(actionStartActivity(WidgetActions.open(context))),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        Image(ImageProvider(R.drawable.lin_logo), contentDescription = null, modifier = GlanceModifier.size(22.dp))
        Text(
          context.getString(R.string.app_name),
          style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
          modifier = GlanceModifier.defaultWeight().padding(start = 8.dp),
        )
        Box(
          modifier =
            GlanceModifier.size(34.dp)
              .background(GlanceTheme.colors.primary)
              .cornerRadius(17.dp)
              .clickable(actionStartActivity(WidgetActions.listen(context))),
          contentAlignment = Alignment.Center,
        ) {
          Image(
            ImageProvider(R.drawable.ic_mic),
            contentDescription = context.getString(R.string.speak),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            modifier = GlanceModifier.size(18.dp),
          )
        }
      }
      Spacer(GlanceModifier.height(8.dp))
      if (answer.isEmpty()) {
        Text(context.getString(R.string.widget_empty), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
      } else {
        if (question.isNotEmpty()) {
          Text(
            question,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
          )
          Spacer(GlanceModifier.height(4.dp))
        }
        Text(answer, maxLines = 6, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
      }
    }
  }

  companion object {
    /** Redraws every placed copy, for when the history changes. Never fatal. */
    suspend fun refresh(context: Context) {
      runCatching { LastAnswerWidget().updateAll(context) }
    }
  }
}

class LastAnswerWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = LastAnswerWidget()
}
