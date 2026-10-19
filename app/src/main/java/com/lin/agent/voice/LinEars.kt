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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.lin.agent.core.models.CatalogModel
import com.lin.agent.core.models.ModelCatalog
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.voice.LinVoices
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lin's own hearing: speech to text on the phone with Moonshine or Whisper through sherpa-onnx,
 * and Silero to hear when somebody starts and stops. Nothing said goes to any other app.
 *
 * Words appear while they are being said - the speech so far is re-read about once a second,
 * which Moonshine does in a fraction of that - and the final text is read once more when Silero
 * hears the pause at the end.
 */
@Singleton
class LinEars
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val store: ModelStore,
  private val settings: SettingsRepository,
) {
  private val loading = Mutex()
  private var recognizer: OfflineRecognizer? = null
  private var loadedFor = ""

  /** The hearing that would listen now, if Lin's own is on and it and Silero are downloaded. */
  suspend fun chosen(): CatalogModel? {
    if (!runCatching { settings.ownVoice.first() }.getOrDefault(true)) return null
    if (!store.isPresent(ModelCatalog.SILERO_VAD)) return null
    val wanted = runCatching { settings.linHearing.first() }.getOrDefault("")
    val present = ModelCatalog.hearings.filter { store.isPresent(it) }
    return present.firstOrNull { it.name == wanted } ?: present.firstOrNull()
  }

  /** Listens until a pause, emitting words as they come. Cancelling closes the microphone. */
  @SuppressLint("MissingPermission")
  fun listen(model: CatalogModel): Flow<Heard> =
    callbackFlow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
          trySend(Heard.Failed("Lin needs the microphone permission to listen."))
          close()
          return@callbackFlow
        }
        val asr = loading.withLock { recognizerFor(model) }
        val vad = speechDetector()
        if (asr == null || vad == null) {
          trySend(Heard.Failed("Lin's hearing could not be loaded."))
          close()
          return@callbackFlow
        }
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record =
          runCatching {
              AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minimum, RATE * 4 * 2))
            }
            .getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
          trySend(Heard.Failed("The microphone could not be started."))
          vad.release()
          close()
          return@callbackFlow
        }
        record.startRecording()

        val reader =
          launch(Dispatchers.IO) {
            val window = FloatArray(WINDOW)
            val speech = ArrayList<Float>(RATE * 10)
            var heardAnything = false
            var started = System.currentTimeMillis()
            var lastPartial = 0L
            var final: String? = null
            while (isActive && final == null) {
              val read = record.read(window, 0, WINDOW, AudioRecord.READ_BLOCKING)
              if (read <= 0) continue
              vad.acceptWaveform(window.copyOf(read))
              if (vad.isSpeechDetected()) {
                if (!heardAnything) started = System.currentTimeMillis()
                heardAnything = true
                for (i in 0 until read) speech.add(window[i])
                val now = System.currentTimeMillis()
                if (now - lastPartial >= PARTIAL_EVERY_MS && speech.size > RATE / 2) {
                  lastPartial = now
                  val sofar = transcribe(asr, speech.toFloatArray())
                  if (sofar.isNotBlank()) trySend(Heard.Partial(sofar))
                }
              }
              // Silero has heard the pause at the end: that segment is the whole thing said.
              while (!vad.empty()) {
                val segment = vad.front().samples
                vad.pop()
                final = transcribe(asr, segment)
              }
              val waited = System.currentTimeMillis() - started
              if (!heardAnything && waited > NOTHING_HEARD_MS) break
              if (heardAnything && waited > LONGEST_MS) {
                vad.flush()
                final = if (!vad.empty()) transcribe(asr, vad.front().samples) else transcribe(asr, speech.toFloatArray())
              }
            }
            val text = final?.trim().orEmpty()
            trySend(if (text.isNotEmpty()) Heard.Final(text) else Heard.Failed("Lin didn't hear anything."))
            close()
          }

        awaitClose {
          reader.cancel()
          runCatching {
            record.stop()
            record.release()
          }
          vad.release()
        }
      }
      .flowOn(Dispatchers.Default)

  private fun transcribe(asr: OfflineRecognizer, samples: FloatArray): String =
    runCatching {
        val stream = asr.createStream()
        try {
          stream.acceptWaveform(samples, RATE)
          asr.decode(stream)
          asr.getResult(stream).text
        } finally {
          stream.release()
        }
      }
      .onFailure { Log.w(TAG, "Could not transcribe", it) }
      .getOrDefault("")

  private fun recognizerFor(model: CatalogModel): OfflineRecognizer? {
    if (loadedFor == model.name) recognizer?.let { return it }
    runCatching { recognizer?.release() }
    recognizer = null
    val folder = store.folderFor(model)
    val names = folder.listFiles()?.map { it.name }.orEmpty()
    val files = LinVoices.hearingFiles(names) ?: return null
    fun path(name: String) = File(folder, name).absolutePath
    val modelConfig =
      when (files) {
        is LinVoices.HearingFiles.Moonshine ->
          OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
              preprocessor = path(files.preprocess),
              encoder = path(files.encode),
              uncachedDecoder = path(files.uncached),
              cachedDecoder = path(files.cached),
            ),
            tokens = path(files.tokens),
            numThreads = THREADS,
          )
        is LinVoices.HearingFiles.Whisper ->
          OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(encoder = path(files.encoder), decoder = path(files.decoder)),
            tokens = path(files.tokens),
            numThreads = THREADS,
          )
      }
    return runCatching { OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = modelConfig)) }
      .onFailure { Log.w(TAG, "Could not load ${model.name}", it) }
      .getOrNull()
      ?.also {
        recognizer = it
        loadedFor = model.name
      }
  }

  /** A fresh detector per listen, so nothing from the last one lingers in its buffer. */
  private fun speechDetector(): Vad? =
    runCatching {
        Vad(
          null,
          VadModelConfig(
            sileroVadModelConfig =
              SileroVadModelConfig(
                model = store.fileFor(ModelCatalog.SILERO_VAD).absolutePath,
                threshold = 0.5f,
                // Long enough for a breath mid-sentence, short enough not to feel like waiting.
                minSilenceDuration = 0.7f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = 30f,
              ),
            sampleRate = RATE,
            numThreads = 1,
          ),
        )
      }
      .onFailure { Log.w(TAG, "Could not load the speech detector", it) }
      .getOrNull()

  private companion object {
    const val TAG = "LinEars"
    const val RATE = 16_000
    /** Silero's window at 16 kHz. */
    const val WINDOW = 512
    const val THREADS = 4
    const val PARTIAL_EVERY_MS = 1_000L
    const val NOTHING_HEARD_MS = 8_000L
    const val LONGEST_MS = 30_000L
  }
}
