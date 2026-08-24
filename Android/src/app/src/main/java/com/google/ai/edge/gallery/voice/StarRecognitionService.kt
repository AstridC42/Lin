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

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "AGRecognitionService"

/**
 * Speech recognition, delegated to whatever recognizer the device already uses.
 *
 * This exists because a voice interaction service is not allowed to omit one: the platform parses
 * `recognitionService` out of the service's XML and rejects the whole configuration when it is
 * missing, which would leave Star Storyteller unselectable as the digital assistant. It is a
 * requirement to satisfy rather than a recognizer worth writing - the app does its own listening
 * inside the chat, where it can show what it heard.
 *
 * So it forwards to the device's configured recognizer rather than pretending to transcribe
 * anything itself. If that recognizer turns out to be this one, it reports an error instead of
 * calling itself forever.
 */
class StarRecognitionService : RecognitionService() {

  private var delegate: SpeechRecognizer? = null

  override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
    if (resolvesToSelf()) {
      Log.w(TAG, "This app is the configured recognizer; refusing to delegate to itself")
      safeError(listener, SpeechRecognizer.ERROR_CLIENT)
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      safeError(listener, SpeechRecognizer.ERROR_CLIENT)
      return
    }
    try {
      // SpeechRecognizer is main-thread only, and onStartListening already arrives there.
      val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
      delegate = recognizer
      recognizer.setRecognitionListener(Forwarder(listener))
      recognizer.startListening(recognizerIntent)
    } catch (e: Exception) {
      Log.e(TAG, "Could not start the device recognizer", e)
      safeError(listener, SpeechRecognizer.ERROR_CLIENT)
    }
  }

  override fun onStopListening(listener: Callback) {
    runCatching { delegate?.stopListening() }
  }

  override fun onCancel(listener: Callback) {
    runCatching { delegate?.cancel() }
    release()
  }

  override fun onDestroy() {
    release()
    super.onDestroy()
  }

  private fun release() {
    runCatching { delegate?.destroy() }
    delegate = null
  }

  /** True when the device's configured recognizer is this very service. */
  private fun resolvesToSelf(): Boolean {
    val configured =
      Settings.Secure.getString(contentResolver, "voice_recognition_service").orEmpty()
    if (configured.isEmpty()) return false
    val component = ComponentName.unflattenFromString(configured) ?: return false
    return component.packageName == packageName
  }

  /** The callback throws once the caller has gone away, which is not worth crashing over. */
  private fun safeError(listener: Callback, code: Int) {
    runCatching { listener.error(code) }
  }

  /** Passes the device recognizer's results back to whoever bound this service. */
  private inner class Forwarder(private val callback: Callback) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      runCatching { callback.readyForSpeech(params ?: Bundle()) }
    }

    override fun onBeginningOfSpeech() {
      runCatching { callback.beginningOfSpeech() }
    }

    override fun onRmsChanged(rmsdB: Float) {
      runCatching { callback.rmsChanged(rmsdB) }
    }

    override fun onBufferReceived(buffer: ByteArray?) {
      runCatching { callback.bufferReceived(buffer ?: ByteArray(0)) }
    }

    override fun onEndOfSpeech() {
      runCatching { callback.endOfSpeech() }
    }

    override fun onError(error: Int) {
      safeError(callback, error)
      release()
    }

    override fun onResults(results: Bundle?) {
      runCatching { callback.results(results ?: Bundle()) }
      release()
    }

    override fun onPartialResults(partialResults: Bundle?) {
      runCatching { callback.partialResults(partialResults ?: Bundle()) }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
      // Nothing in the platform defines these, and forwarding an unknown event has no meaning.
    }
  }
}
