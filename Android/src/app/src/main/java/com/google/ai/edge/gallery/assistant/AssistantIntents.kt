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

package com.google.ai.edge.gallery.assistant

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.ai.edge.gallery.voice.EXTRA_START_LISTENING

/**
 * What an incoming intent is asking the assistant to do.
 *
 * @property query text to send as the first message; empty when the intent only opened the app.
 * @property imageUris images to attach to that first message, as content URI strings.
 * @property startListening open the microphone immediately instead of waiting for the keyboard.
 *   Set when the assistant was summoned hands-free - the assist gesture, a headset button, a car's
 *   voice control - where nobody is in a position to reach for the screen.
 */
data class AssistantLaunchRequest(
  val query: String = "",
  val imageUris: List<String> = listOf(),
  val startListening: Boolean = false,
) {
  val isEmpty: Boolean
    get() = query.isEmpty() && imageUris.isEmpty() && !startListening
}

/**
 * Translates the intents that make Lin usable as the phone's assistant into a first
 * message.
 *
 * Handled:
 * - `ASSIST` and `VOICE_COMMAND`: the system assist gesture and the "talk to my assistant"
 *   shortcut. They carry no text, so they just open the chat ready for input.
 * - `SEARCH` / `WEB_SEARCH`: a spoken or typed query handed over by the launcher.
 * - `PROCESS_TEXT`: text the user selected in another app and sent here.
 * - `SEND` / `SEND_MULTIPLE`: text or images shared from another app - the fastest way to get a
 *   photo in front of the assistant.
 */
object AssistantIntents {

  fun parse(intent: Intent?): AssistantLaunchRequest {
    if (intent == null) return AssistantLaunchRequest()
    return when (intent.action) {
      Intent.ACTION_ASSIST,
      "android.intent.action.VOICE_COMMAND" ->
        AssistantLaunchRequest(
          // Only when the voice session summoned us. The same action arriving from a launcher
          // shortcut is someone tapping an icon, who did not ask to be recorded.
          startListening = intent.getBooleanExtra(EXTRA_START_LISTENING, false)
        )

      Intent.ACTION_SEARCH,
      Intent.ACTION_WEB_SEARCH ->
        AssistantLaunchRequest(query = intent.getStringExtra(SearchManager.QUERY).orEmpty().trim())

      Intent.ACTION_PROCESS_TEXT ->
        AssistantLaunchRequest(
          query =
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().trim()
        )

      Intent.ACTION_SEND ->
        AssistantLaunchRequest(
          query = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim(),
          imageUris = listOfNotNull(intent.streamExtra()).map(Uri::toString),
        )

      Intent.ACTION_SEND_MULTIPLE ->
        AssistantLaunchRequest(
          query = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim(),
          imageUris = intent.streamExtras().map(Uri::toString),
        )

      else -> AssistantLaunchRequest()
    }
  }

  /** True when [intent] is one this object knows how to turn into a message. */
  fun isAssistantIntent(intent: Intent?): Boolean =
    when (intent?.action) {
      Intent.ACTION_ASSIST,
      "android.intent.action.VOICE_COMMAND",
      Intent.ACTION_SEARCH,
      Intent.ACTION_WEB_SEARCH,
      Intent.ACTION_PROCESS_TEXT,
      Intent.ACTION_SEND,
      Intent.ACTION_SEND_MULTIPLE -> true
      else -> false
    }

  @Suppress("DEPRECATION")
  private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      getParcelableExtra(Intent.EXTRA_STREAM)
    }

  @Suppress("DEPRECATION")
  private fun Intent.streamExtras(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
      getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}
