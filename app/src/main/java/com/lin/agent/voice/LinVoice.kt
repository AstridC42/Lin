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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.lin.agent.core.models.CatalogModel
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.voice.LinVoices
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lin's own voice: text to speech on the phone with a Piper or Kokoro model, through sherpa-onnx.
 * Nothing about what Lin says goes to any other app or service.
 *
 * Spoken a sentence at a time: the first sentence is made and starts playing while the next is
 * still being made, so a long answer starts in about the time one sentence takes, not the whole
 * answer.
 */
@Singleton
class LinVoice
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val store: ModelStore,
  private val settings: SettingsRepository,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val loading = Mutex()
  /** One sentence at a time through the engine, even when a new reply interrupts an old one. */
  private val generating = Mutex()
  private var engine: OfflineTts? = null
  private var loadedFor: String = ""
  @Volatile private var speaking: Job? = null
  @Volatile private var track: AudioTrack? = null
  private var focus: AudioFocusRequest? = null

  /** The voice that would speak now, if Lin's own voice is on and one is downloaded. */
  suspend fun chosen(): CatalogModel? {
    if (!runCatching { settings.ownVoice.first() }.getOrDefault(true)) return null
    val wanted = runCatching { settings.linVoice.first() }.getOrDefault("")
    val present = ModelCatalog.voices.filter { store.isPresent(it) }
    return present.firstOrNull { it.name == wanted } ?: present.firstOrNull()
  }

  /**
   * Speaks [text], then calls [whenDone]. False when there is no voice of Lin's own to speak
   * with, so the caller can use the phone's.
   */
  suspend fun say(text: String, whenDone: (() -> Unit)? = null, override: CatalogModel? = null, speaker: String? = null): Boolean {
    val model = override ?: chosen() ?: return false
    val tts = loading.withLock { engineFor(model) } ?: return false
    stop()
    val rate = runCatching { settings.speechRate.first() }.getOrDefault(1f)
    val sid =
      if (model == ModelCatalog.KOKORO) {
        val id = speaker ?: runCatching { settings.kokoroSpeaker.first() }.getOrDefault("af_sarah")
        LinVoices.KOKORO_SPEAKERS.indexOf(id).coerceAtLeast(0)
      } else 0
    speaking = scope.launch { play(tts, LinVoices.sentences(text), sid, rate, whenDone) }
    return true
  }

  private suspend fun play(tts: OfflineTts, sentences: List<String>, sid: Int, rate: Float, whenDone: (() -> Unit)?) {
    val sampleRate = tts.sampleRate()
    val out = openTrack(sampleRate) ?: run { whenDone?.invoke(); return }
    track = out
    holdFocus(true)
    val made = Channel<FloatArray>(capacity = 2)
    // Making and playing at once: the next sentence is generated while this one plays.
    val maker =
      scope.launch {
        for (sentence in sentences) {
          if (!isActive) break
          val audio = runCatching { generating.withLock { tts.generate(sentence, sid, rate) } }.onFailure { Log.w(TAG, "Could not speak a sentence", it) }.getOrNull()
          if (audio != null && audio.samples.isNotEmpty()) made.send(audio.samples)
        }
        made.close()
      }
    var written = 0L
    var finished = false
    try {
      out.play()
      for (samples in made) {
        out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        written += samples.size
      }
      // Wait for the last of it to be heard, not just handed over, before saying it is done.
      while (out.playbackHeadPosition < written && out.playState == AudioTrack.PLAYSTATE_PLAYING) delay(30)
      finished = true
    } catch (stopped: kotlinx.coroutines.CancellationException) {
      maker.cancel()
      throw stopped
    } finally {
      runCatching { out.stop(); out.release() }
      if (track === out) track = null
      holdFocus(false)
      if (finished) whenDone?.invoke()
    }
  }

  /** Stops mid-sentence. Whoever was waiting for the end is not called: it was interrupted. */
  fun stop() {
    speaking?.cancel()
    speaking = null
    runCatching { track?.pause(); track?.flush() }
  }

  private fun engineFor(model: CatalogModel): OfflineTts? {
    if (loadedFor == model.name) engine?.let { return it }
    runCatching { engine?.release() }
    engine = null
    val folder = store.folderFor(model)
    val names = folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).invariantSeparatorsPath }.toList()
    val files = LinVoices.voiceFiles(names) ?: return null
    fun path(name: String) = File(folder, name).absolutePath
    val config =
      OfflineTtsConfig(
        model =
          OfflineTtsModelConfig(
            vits =
              if (files.kokoro) OfflineTtsVitsModelConfig()
              else OfflineTtsVitsModelConfig(model = path(files.model), tokens = path(files.tokens), dataDir = path(files.dataDir)),
            kokoro =
              if (files.kokoro) {
                OfflineTtsKokoroModelConfig(model = path(files.model), voices = path(files.voices), tokens = path(files.tokens), dataDir = path(files.dataDir))
              } else OfflineTtsKokoroModelConfig(),
            numThreads = THREADS,
          )
      )
    return runCatching { OfflineTts(null, config) }
      .onFailure { Log.w(TAG, "Could not load ${model.name}", it) }
      .getOrNull()
      ?.also {
        engine = it
        loadedFor = model.name
      }
  }

  private fun openTrack(sampleRate: Int): AudioTrack? =
    runCatching {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
          )
          .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
          )
          .setTransferMode(AudioTrack.MODE_STREAM)
          .setBufferSizeInBytes(maxOf(minimum, sampleRate * 4 / 2))
          .build()
      }
      .onFailure { Log.w(TAG, "Could not open audio output", it) }
      .getOrNull()

  /** Music and podcasts dip while Lin talks, and come back after, as they do for any assistant. */
  private fun holdFocus(on: Boolean) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    if (on) {
      val request =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
          .build()
      focus = request
      runCatching { audio.requestAudioFocus(request) }
    } else {
      focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
      focus = null
    }
  }

  private companion object {
    const val TAG = "LinVoice"
    const val THREADS = 4
  }
}
