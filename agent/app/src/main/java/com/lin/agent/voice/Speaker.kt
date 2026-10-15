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
import android.speech.tts.Voice
import android.util.Log
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.voice.VoiceOption
import com.lin.agent.core.voice.chosenVoice
import com.lin.agent.core.voice.forSpeaking
import com.lin.agent.core.voice.usableVoices
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
class Speaker
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val settings: SettingsRepository,
) {

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
    runCatching {
        applyChoices(live)
        live.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
      }
      .onFailure { Log.w(TAG, "Could not speak", it) }
  }

  /**
   * Speaks a sample in the voice being chosen, without it being a reply.
   *
   * The settings screen needs this: a list of names like `en-gb-x-gbb-local` tells nobody
   * what they are picking, and a voice is the one setting that cannot be judged by reading.
   */
  fun preview(voiceName: String, sample: String) {
    val live = engine ?: start()
    if (live == null || !ready) return
    runCatching {
      applyChoices(live, override = voiceName)
      live.speak(sample, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }
  }

  /** Every voice the current engine has, for the settings screen to offer. */
  fun voices(): List<VoiceOption> {
    val live = engine ?: start()
    if (live == null || !ready) return emptyList()
    val all =
      runCatching { live.voices.orEmpty().map { it.asOption() } }.getOrDefault(emptyList())
    return usableVoices(all, Locale.getDefault().language)
  }

  /**
   * The chosen voice, speed and pitch, applied before speaking.
   *
   * Read here rather than held, because the settings screen changes them while this object
   * lives: a value cached at start-up would mean the voice somebody just picked takes effect
   * next time they open the app. Blocking on a DataStore read of three keys is microseconds,
   * and this is already on the way to an engine that takes longer than that to say a word.
   */
  private fun applyChoices(live: TextToSpeech, override: String = "") {
    val wanted =
      runCatching {
          runBlocking {
            Choices(
              voice = override.ifBlank { settings.voiceName.first() },
              rate = settings.speechRate.first(),
              pitch = settings.speechPitch.first(),
            )
          }
        }
        .getOrNull() ?: return

    runCatching { live.setSpeechRate(wanted.rate) }
    runCatching { live.setPitch(wanted.pitch) }

    if (wanted.voice.isBlank()) return
    val available = runCatching { live.voices.orEmpty().toList() }.getOrDefault(emptyList())
    val options = available.map { it.asOption() }
    // Falls back to the best available rather than going silent: engines get switched and
    // language packs removed, and a stored name outliving its voice is ordinary.
    val choice = chosenVoice(usableVoices(options, Locale.getDefault().language), wanted.voice)
    val match = available.firstOrNull { it.name == choice?.name } ?: return
    runCatching { live.voice = match }
  }

  private fun Voice.asOption() =
    VoiceOption(
      name = name.orEmpty(),
      language = locale?.language.orEmpty(),
      country = locale?.country.orEmpty(),
      quality = quality,
      needsNetwork = isNetworkConnectionRequired,
      // The engine lists voices it has not downloaded, which speak as nothing at all.
      installed = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
    )

  private data class Choices(val voice: String, val rate: Float, val pitch: Float)

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
