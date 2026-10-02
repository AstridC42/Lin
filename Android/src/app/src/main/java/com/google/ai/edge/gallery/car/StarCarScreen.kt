/*
 * Copyright 2026 The Star Storyteller Authors
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

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.voice.EXTRA_START_LISTENING
import com.google.ai.edge.gallery.voice.WakeWordService
import com.google.ai.edge.gallery.voice.WakeWordSettings

/**
 * The assistant's screen in the car.
 *
 * Short by design. Android Auto caps how much can be on screen while moving, and a list a driver
 * has to read through is worse than no list - so this offers the few things that are genuinely
 * worth a glance and puts everything else behind speaking.
 *
 * Nothing here renders a conversation. Replies are spoken by the phone; the car screen is how you
 * start one without picking the phone up.
 */
class StarCarScreen(carContext: CarContext) : Screen(carContext) {

  override fun onGetTemplate(): Template {
    val items =
      ItemList.Builder()
        .addItem(
          Row.Builder()
            .setTitle(carContext.getString(R.string.car_talk_title))
            .addText(carContext.getString(R.string.car_talk_subtitle))
            .setImage(icon(R.mipmap.ic_launcher))
            .setOnClickListener { startListening() }
            .setBrowsable(false)
            .build()
        )
        .addItem(
          Row.Builder()
            .setTitle(carContext.getString(R.string.car_wake_word_title))
            .addText(
              carContext.getString(
                if (wakeWordEnabled()) R.string.car_wake_word_on else R.string.car_wake_word_off
              )
            )
            .setOnClickListener { toggleWakeWord() }
            .setBrowsable(false)
            .build()
        )
        .build()

    return ListTemplate.Builder()
      .setSingleList(items)
      .setTitle(carContext.getString(R.string.app_name))
      .setHeaderAction(Action.APP_ICON)
      .build()
  }

  private fun icon(resId: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

  /**
   * Opens the assistant on the phone with the microphone live.
   *
   * The car app cannot run speech recognition itself - the Car App Library gives no access to the
   * microphone - so the phone does the listening and the speaking, which is also where the model
   * already is.
   */
  private fun startListening() {
    val intent =
      Intent(carContext, com.google.ai.edge.gallery.MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action = Intent.ACTION_ASSIST
        putExtra(EXTRA_START_LISTENING, true)
      }
    // startActivity on CarContext routes to the phone rather than the car display, which is what
    // is wanted: the chat is a phone screen and must not be drawn while driving.
    runCatching { carContext.startActivity(intent) }
  }

  private fun wakeWordEnabled(): Boolean =
    CarSettings.readAssistantSettings(carContext)?.wakeWordEnabled == true

  private fun toggleWakeWord() {
    val settings = CarSettings.readAssistantSettings(carContext) ?: return
    val turningOn = !settings.wakeWordEnabled
    CarSettings.saveAssistantSettings(
      carContext,
      settings.toBuilder().setWakeWordEnabled(turningOn).build(),
    )
    if (turningOn) {
      WakeWordService.start(carContext, WakeWordSettings.phraseOrDefault(settings.wakeWordPhrase))
    } else {
      WakeWordService.stop(carContext)
    }
    invalidate()
  }
}
