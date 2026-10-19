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
import android.speech.tts.UtteranceProgressListener
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
  /** Lin's own voice, used instead of the phone's whenever it is on and downloaded. */
  private val linVoice: LinVoice,
) {

  @Volatile private var engine: TextToSpeech? = null

  /**
   * Completed when the engine has said whether it started.
   *
   * This is the whole bug this class had. A TextToSpeech reports readiness in a callback some
   * way after its constructor returns, and every method here used to check a plain `ready`
   * flag and give up when it was still false - which it always was on the first call. So the
   * first spoken reply after each launch was silently dropped, every preview was silent, and
   * the settings screen asked for the voice list once, got an empty one, and showed "no
   * voices" for ever. One flag, three features that looked unbuilt.
   */
  private var starting: CompletableDeferred<Boolean>? = null

  /** Called when the engine finishes speaking, so a conversation can carry on by voice. */
  @Volatile private var onFinished: (() -> Unit)? = null

  /**
   * Reads a reply out loud, and says when it has finished.
   *
   * [whenDone] is how voice chat works: the microphone may only reopen once Lin has stopped
   * talking, or it hears Lin.
   */
  suspend fun say(reply: String, whenDone: (() -> Unit)? = null) {
    val text = forSpeaking(reply)
    if (text.isBlank()) {
      whenDone?.invoke()
      return
    }
    // Lin's own voice first; the phone's text-to-speech only when Lin has none of its own yet.
    runCatching { engine?.stop() }
    if (runCatching { linVoice.say(text, whenDone) }.getOrDefault(false)) return

    val live = awaitEngine()
    if (live == null) {
      Log.i(TAG, "Nothing to speak with")
      // The caller may be waiting to listen again; not speaking must not strand it.
      whenDone?.invoke()
      return
    }
    onFinished = whenDone
    runCatching {
        applyChoices(live)
        live.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
      }
      .onFailure {
        Log.w(TAG, "Could not speak", it)
        onFinished = null
        whenDone?.invoke()
      }
  }

  /**
   * Speaks a sample in the voice being chosen, without it being a reply.
   *
   * The settings screen needs this: a list of names like `en-gb-x-gbb-local` tells nobody
   * what they are picking, and a voice is the one setting that cannot be judged by reading.
   */
  suspend fun preview(voiceName: String, sample: String) {
    val live = awaitEngine() ?: return
    onFinished = null
    runCatching {
      applyChoices(live, override = voiceName)
      live.speak(sample, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }
  }

  /** Every voice the current engine has, for the settings screen to offer. */
  suspend fun voices(): List<VoiceOption> {
    val live = awaitEngine() ?: return emptyList()
    val all =
      runCatching { live.voices.orEmpty().map { it.asOption() } }.getOrDefault(emptyList())
    return usableVoices(all, Locale.getDefault().language)
  }

  /**
   * The engine, once it has started, or null if it will not.
   *
   * Waits rather than gives up. The timeout is generous because a cold engine on a phone that
   * has not spoken since boot genuinely takes a second or two, and the alternative to waiting
   * is the silence this replaced.
   */
  private suspend fun awaitEngine(): TextToSpeech? {
    val live = startEngine() ?: return null
    val signal = starting ?: return null
    val ok = withTimeoutOrNull(READY_MS) { signal.await() } ?: false
    return if (ok) live else null
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
    onFinished = null
    runCatching { engine?.stop() }
    linVoice.stop()
  }

  /** A sample in one of Lin's own voices, for the settings screen. False if it is not downloaded. */
  suspend fun previewOwn(model: com.lin.agent.core.models.CatalogModel, speaker: String?, sample: String): Boolean =
    runCatching { linVoice.say(sample, null, override = model, speaker = speaker) }.getOrDefault(false)

  fun shutdown() {
    runCatching {
      engine?.stop()
      engine?.shutdown()
    }
    engine = null
    starting = null
    onFinished = null
  }

  @Synchronized
  private fun startEngine(): TextToSpeech? {
    engine?.let {
      return it
    }
    val signal = CompletableDeferred<Boolean>()
    return runCatching {
        TextToSpeech(context) { status ->
            signal.complete(status == TextToSpeech.SUCCESS)
          }
          .also {
            engine = it
            starting = signal
            // One listener for the life of the engine: whoever is speaking sets what should
            // happen when it stops, and clears it afterwards.
            runCatching { it.setOnUtteranceProgressListener(Finished()) }
          }
      }
      .onFailure {
        Log.w(TAG, "Could not start text to speech", it)
        signal.complete(false)
      }
      .getOrNull()
  }

  /** Tells whoever asked that Lin has stopped talking. */
  private inner class Finished : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = Unit

    override fun onDone(utteranceId: String?) {
      val waiting = onFinished
      onFinished = null
      waiting?.invoke()
    }

    @Deprecated("Required by the base class", ReplaceWith(""))
    override fun onError(utteranceId: String?) = onDone(utteranceId)

    override fun onError(utteranceId: String?, errorCode: Int) = onDone(utteranceId)

    // Stopped on purpose - the user sent something else, or left. Whoever was waiting is no
    // longer waiting for anything, and reopening the microphone here would be a surprise.
    override fun onStop(utteranceId: String?, interrupted: Boolean) {
      onFinished = null
    }
  }

  private companion object {
    const val TAG = "LinSpeaker"
    const val READY_MS = 4_000L
    const val UTTERANCE = "lin-reply"
  }
}
