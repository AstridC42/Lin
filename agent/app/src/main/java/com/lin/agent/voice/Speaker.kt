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

package com.lin.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.lin.agent.core.voice.forSpeaking
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a reply out loud.
 *
 * The engine is created on first use and kept, because starting one takes long enough to hear:
 * created per reply, the first word of every spoken answer arrives after a pause that reads as
 * the app having not understood.
 *
 * Nothing here decides *whether* to speak - that is
 * [com.lin.agent.core.voice.shouldSpeak], which is a rule rather than a side effect and is
 * tested as one.
 */
@Singleton
class Speaker @Inject constructor(@ApplicationContext private val context: Context) {

  @Volatile private var engine: TextToSpeech? = null

  @Volatile private var ready = false

  fun say(reply: String) {
    val text = forSpeaking(reply)
    if (text.isBlank()) return

    val live = engine ?: start()
    if (live == null || !ready) {
      // Not an error worth showing. The reply is already on screen; losing the audio is a
      // smaller thing than an error card about a text-to-speech engine.
      Log.i(TAG, "Nothing to speak with yet")
      return
    }
    runCatching { live.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) }
      .onFailure { Log.w(TAG, "Could not speak", it) }
  }

  /** Stops mid-sentence. Called when the user sends something else, or leaves. */
  fun stop() {
    runCatching { engine?.stop() }
  }

  fun shutdown() {
    runCatching {
      engine?.stop()
      engine?.shutdown()
    }
    engine = null
    ready = false
  }

  @Synchronized
  private fun start(): TextToSpeech? {
    engine?.let {
      return it
    }
    return runCatching {
        TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }.also {
          engine = it
        }
      }
      .onFailure { Log.w(TAG, "Could not start text to speech", it) }
      .getOrNull()
  }

  private companion object {
    const val TAG = "LinSpeaker"
    const val UTTERANCE = "lin-reply"
  }
}
