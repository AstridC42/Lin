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
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lin.agent.AssistActivity
import com.lin.agent.MainActivity
import com.lin.agent.R
import com.lin.agent.voice.WakeService

/** Ways into Lin from a widget, shared by both of them. */
internal object WidgetActions {
  /** The app, to type. */
  fun open(context: Context) = Intent(context, MainActivity::class.java)

  /** The assistant panel, already listening - the same as the "listen" shortcut. */
  fun listen(context: Context) = Intent().setClassName(context, "com.lin.agent.ListenActivity")

  /**
   * The assistant panel, asking [question] straight away. No token, so every tap is its own
   * question; a distinct address per question, so Android keeps their taps apart.
   */
  fun ask(context: Context, question: String) =
    Intent(context, AssistActivity::class.java)
      .putExtra(WakeService.EXTRA_SAID, question)
      .setData(Uri.parse("lin://ask/" + Uri.encode(question)))
}

/**
 * "Ask Lin" on the home screen: a pill like a search bar. Tap it to type, tap the microphone to
 * talk. Given more height, it adds a row of things to ask with one tap.
 */
class AskWidget : GlanceAppWidget() {

  override val sizeMode = SizeMode.Responsive(setOf(SMALL, TALL))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent { GlanceTheme { AskContent() } }
  }

  @Composable
  private fun AskContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    Column(modifier = GlanceModifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
      Row(
        modifier =
          GlanceModifier.fillMaxWidth()
            .height(52.dp)
            .background(GlanceTheme.colors.surface)
            .cornerRadius(26.dp)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Image(ImageProvider(R.drawable.lin_logo), contentDescription = null, modifier = GlanceModifier.size(28.dp))
        Text(
          context.getString(R.string.widget_ask),
          style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp),
          modifier = GlanceModifier.defaultWeight().padding(start = 10.dp).clickable(actionStartActivity(WidgetActions.open(context))),
        )
        Box(
          modifier =
            GlanceModifier.size(40.dp)
              .background(GlanceTheme.colors.primary)
              .cornerRadius(20.dp)
              .clickable(actionStartActivity(WidgetActions.listen(context))),
          contentAlignment = Alignment.Center,
        ) {
          Image(
            ImageProvider(R.drawable.ic_mic),
            contentDescription = context.getString(R.string.speak),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            modifier = GlanceModifier.size(20.dp),
          )
        }
      }
      if (size.height >= TALL.height) {
        Spacer(GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
          listOf(R.string.empty_today, R.string.empty_missed, R.string.empty_music).forEachIndexed { index, question ->
            if (index > 0) Spacer(GlanceModifier.width(6.dp))
            Chip(context.getString(question), GlanceModifier.defaultWeight())
          }
        }
      }
    }
  }

  @Composable
  private fun Chip(question: String, modifier: GlanceModifier) {
    val context = LocalContext.current
    Box(
      modifier =
        modifier
          .height(44.dp)
          .background(GlanceTheme.colors.secondaryContainer)
          .cornerRadius(16.dp)
          .padding(horizontal = 8.dp)
          .clickable(actionStartActivity(WidgetActions.ask(context, question))),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        question,
        maxLines = 2,
        style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium),
      )
    }
  }

  private companion object {
    val SMALL = DpSize(180.dp, 56.dp)
    val TALL = DpSize(250.dp, 110.dp)
  }
}

class AskWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = AskWidget()
}
