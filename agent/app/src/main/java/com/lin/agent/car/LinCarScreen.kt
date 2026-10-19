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

package com.lin.agent.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lin.agent.R
import com.lin.agent.core.car.CarInfo
import com.lin.agent.core.car.CarLine
import com.lin.agent.core.car.carPane
import com.lin.agent.core.media.chooseSession
import com.lin.agent.core.remind.whenInWords
import com.lin.agent.model.activeControllers
import com.lin.agent.model.asSession
import com.lin.agent.notifications.NotificationRelay
import com.lin.agent.remind.ReminderBook
import com.lin.agent.voice.WakeService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one screen Lin has in a car: a microphone, and what is worth knowing.
 *
 * Both halves are deliberate. The microphone is the whole point - in a car there is no
 * keyboard and no reason to look at a screen, so the only interface that makes sense is one
 * button that starts listening. The information beside it is what somebody would otherwise
 * pick the phone up to check, which is the thing this screen exists to stop them doing.
 *
 * **What the button does.** It opens the microphone by starting the same listening service the
 * notification starts, and then "Hey Lin, what's the traffic like" is answered out loud. It
 * does not run a question by itself, and that is not a shortcut: the answer has to be spoken
 * to be any use while driving, and the thing that speaks it is the conversation on the phone.
 *
 * **What happens when there is nothing to report.** A pane with no rows is not a legal
 * template - it throws - so with nothing to say this falls back to a message template carrying
 * the same button. That is also the honest answer to a host that says there is only room for
 * the button: see [carPane], where a limit of nothing is a real answer rather than an error.
 */
class LinCarScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun reminders(): ReminderBook

    fun relay(): NotificationRelay

    fun settings(): com.lin.agent.core.settings.SettingsRepository
  }

  private val deps by lazy {
    EntryPointAccessors.fromApplication(carContext.applicationContext, Deps::class.java)
  }

  private var lines: Map<CarInfo, CarLine> = emptyMap()

  init {
    lifecycle.addObserver(this)
  }

  /**
   * Read when the screen appears rather than kept up to date.
   *
   * A car screen is looked at for a second at a time, and the host redraws it when it comes
   * back to the front. Watching three flows to keep a screen fresh that nobody is looking at
   * would cost battery in exchange for nothing.
   */
  override fun onStart(owner: LifecycleOwner) {
    refresh()
    // Opening Lin in the car puts the conversation back if it is switched on and was dismissed,
    // so there is always a message to reply to.
    lifecycleScope.launch {
      val wanted = runCatching { deps.settings().carChat.first() }.getOrDefault(false)
      if (wanted && !CarChat.isShowing(carContext)) CarChat.greet(carContext)
    }
  }

  override fun onGetTemplate(): Template {
    val mic = micAction()
    val rows = carPane(lines, roomForRows())

    if (rows.isEmpty()) {
      return MessageTemplate.Builder(carContext.getString(R.string.car_screen_idle))
        .setTitle(carContext.getString(R.string.app_name))
        .setHeaderAction(Action.APP_ICON)
        .addAction(mic)
        .build()
    }

    val pane = Pane.Builder()
    rows.forEach { line ->
      val row = Row.Builder().setTitle(line.title)
      if (line.detail.isNotBlank()) row.addText(line.detail)
      pane.addRow(row.build())
    }
    pane.addAction(mic)

    return PaneTemplate.Builder(pane.build())
      .setTitle(carContext.getString(R.string.app_name))
      .setHeaderAction(Action.APP_ICON)
      .build()
  }

  /** How many rows the host says it will show. Asked, never assumed. */
  private fun roomForRows(): Int =
    runCatching {
        carContext
          .getCarService(ConstraintManager::class.java)
          .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
      }
      .getOrDefault(2)

  private fun micAction(): Action =
    Action.Builder()
      .setTitle(
        carContext.getString(
          if (WakeService.isListening) R.string.car_screen_stop else R.string.car_screen_listen
        )
      )
      .setIcon(
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()
      )
      // Coloured, because it is the only thing on this screen anybody should have to find
      // without looking for it - and marked primary, because that is what makes the colour
      // legal. The library restricts a background colour to the primary action
      // (ActionsConstraints.mRestrictBackgroundColorToPrimaryAction), so a coloured action
      // without the flag is a template the host rejects rather than draws.
      .setFlags(Action.FLAG_PRIMARY)
      .setBackgroundColor(CarColor.BLUE)
      .setOnClickListener { micPressed() }
      .build()

  private fun micPressed() {
    if (
      carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      // The host asks on the phone and tells the driver to look at it, which is the only way
      // this can go: a permission dialog is not something a car screen may draw.
      runCatching {
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
          if (Manifest.permission.RECORD_AUDIO in granted) startListening() else say(R.string.car_screen_no_mic)
        }
      }
        .onFailure { say(R.string.car_screen_no_mic) }
      return
    }

    if (WakeService.isListening) {
      WakeService.stop(carContext)
      say(R.string.car_screen_stopped)
    } else {
      startListening()
    }
    invalidate()
  }

  /**
   * Opens the microphone, and says so if it cannot.
   *
   * The failure is a real one rather than defensive habit: a microphone foreground service may
   * not be started from the background, and whether the car host binding this app counts as
   * the foreground is the host's business, not ours. If Android refuses, the driver is told to
   * use the notification on the phone - which is the path that is always allowed - instead of
   * pressing a button that silently does nothing.
   */
  private fun startListening() {
    val started =
      runCatching { carContext.startForegroundService(Intent(carContext, WakeService::class.java)) }
    if (started.isFailure) {
      Log.w(TAG, "The car could not open the microphone", started.exceptionOrNull())
      CarNotification.show(carContext, true)
      say(R.string.car_screen_use_notification)
    } else {
      say(R.string.car_screen_listening)
    }
    invalidate()
  }

  private fun say(text: Int) {
    runCatching { CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show() }
  }

  private fun refresh() {
    lifecycleScope.launch {
      lines = runCatching { gather() }.getOrDefault(emptyMap())
      invalidate()
    }
  }

  /** What is worth knowing, in the words the car shows. The order is [CarInfo]'s. */
  private suspend fun gather(): Map<CarInfo, CarLine> {
    val found = mutableMapOf<CarInfo, CarLine>()

    if (
      carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      found[CarInfo.TROUBLE] =
        CarLine(
          carContext.getString(R.string.car_screen_trouble_mic),
          carContext.getString(R.string.car_screen_trouble_mic_detail),
        )
    }

    if (WakeService.isListening) {
      found[CarInfo.LISTENING] =
        CarLine(
          carContext.getString(R.string.car_screen_row_listening),
          carContext.getString(R.string.car_screen_row_listening_detail),
        )
    }

    val now = System.currentTimeMillis()
    deps.reminders().ahead(now).firstOrNull()?.let {
      found[CarInfo.REMINDER] = CarLine(it.text, whenInWords(it.atMillis, now, ZoneId.systemDefault()))
    }

    val waiting = deps.relay().repliable.value
    if (waiting.isNotEmpty()) {
      found[CarInfo.MESSAGES] =
        CarLine(
          carContext.resources.getQuantityString(
            R.plurals.car_screen_waiting,
            waiting.size,
            waiting.size,
          ),
          waiting.map { it.from }.distinct().take(3).joinToString(", "),
        )
    }

    // Best effort: seeing what is playing needs notification access, and a car screen is not
    // the place to ask for it. Without it this row is simply absent.
    runCatching {
        activeControllers(carContext)?.map { it.asSession(carContext) }?.let(::chooseSession)
      }
      .getOrNull()
      ?.let { session ->
        val what = listOf(session.title, session.artist).filter { it.isNotBlank() }
        if (what.isNotEmpty()) {
          found[CarInfo.PLAYING] = CarLine(what.first(), what.drop(1).joinToString(" - "))
        }
      }

    return found
  }

  private companion object {
    const val TAG = "LinCarScreen"
  }
}
