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

package com.google.ai.edge.gallery.voice

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.google.ai.edge.gallery.MainActivity

private const val TAG = "AGVoiceSession"

/** Extra set on the launch intent when the assistant was summoned hands-free. */
const val EXTRA_START_LISTENING = "com.starstoryteller.assistant.START_LISTENING"

/**
 * One summoning of the assistant.
 *
 * The system creates this when the assist gesture fires, the power button is held, a Bluetooth or
 * steering-wheel voice button is pressed, or another app asks for the assistant. Every one of
 * those is a request that arrives with the phone possibly locked, possibly in a pocket, possibly
 * in a car mount - so the session opens the chat and starts listening rather than waiting for
 * someone to reach for the keyboard.
 *
 * ### On wake words
 *
 * There is no supported way for an app like this one to register a custom always-on wake word.
 * `AlwaysOnHotwordDetector`, reachable from a voice interaction service, only recognises keyphrases
 * that the device's own low-power audio DSP has been built with and enrolled - in practice the
 * manufacturer's phrase, not one this app can add. Anything else means keeping the microphone open
 * continuously from a foreground service, which is a different bargain in battery and privacy and
 * is offered separately and explicitly rather than pretended to be a wake word here.
 *
 * What this session does give is every hands-free route the platform actually sanctions, which
 * covers the driving case: a car's voice button, a headset button, the assist gesture.
 */
class StarVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    Log.d(TAG, "Assistant summoned (showFlags=$showFlags)")
    launchAssistant(query = "", listen = true)
    // The chat is a full activity, so the session UI itself has nothing left to display. Leaving
    // it up would put an empty overlay over the app that was just opened.
    hide()
  }

  /**
   * Handles a summoning that carried a query, for example a voice command routed by the system
   * rather than spoken into the app.
   */
  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    val query =
      state.assistData?.getString(SearchManager.QUERY).orEmpty().ifEmpty {
        state.assistData?.getString(Intent.EXTRA_TEXT).orEmpty()
      }
    if (query.isNotBlank()) {
      Log.d(TAG, "Assistant summoned with a query")
      launchAssistant(query = query.trim(), listen = false)
      hide()
    }
  }

  private fun launchAssistant(query: String, listen: Boolean) {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        // The session is not an activity, so a new task is required; and the assistant should
        // come back to whatever conversation is already open rather than stacking a second copy.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action = Intent.ACTION_ASSIST
        if (query.isNotEmpty()) putExtra(SearchManager.QUERY, query)
        if (listen) putExtra(EXTRA_START_LISTENING, true)
      }
    try {
      startAssistantActivity(intent)
    } catch (e: Exception) {
      // startAssistantActivity is refused when the device is locked and the app is not allowed to
      // show over the keyguard. Falling back keeps the summon working; the system then prompts to
      // unlock rather than silently doing nothing.
      Log.w(TAG, "startAssistantActivity refused, falling back to a normal launch", e)
      try {
        context.startActivity(intent)
      } catch (e2: Exception) {
        Log.e(TAG, "Could not open the assistant", e2)
      }
    }
  }
}
