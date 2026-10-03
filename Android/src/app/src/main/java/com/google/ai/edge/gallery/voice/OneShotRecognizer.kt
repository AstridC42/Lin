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

package com.google.ai.edge.gallery.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "AGOneShotRecognizer"

/**
 * Listens once and reports what it heard.
 *
 * The chat screen dictates through a view model, which cannot be reached from a voice interaction
 * session - a session is neither an activity nor a lifecycle owner, so there is no store to hold
 * one. This is the same job with nothing around it.
 *
 * Every callback here fires on the main thread, because [SpeechRecognizer] requires it and because
 * everything waiting on the result is a view.
 */
class OneShotRecognizer(private val context: Context) {

  private var recognizer: SpeechRecognizer? = null
  private var finished = false

  /**
   * Opens the microphone.
   *
   * [onFinal] runs exactly once, with the empty string when nothing was understood. Callers put the
   * panel into its next state from there, and a callback that could fire twice - or not at all -
   * would leave that panel stuck on "Listening" with the microphone already closed.
   */
  fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
    cancel()
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      Log.w(TAG, "No speech recognizer is available on this device")
      onFinal("")
      return
    }

    finished = false
    val finish = { text: String ->
      if (!finished) {
        finished = true
        onFinal(text)
      }
    }

    val created =
      try {
        SpeechRecognizer.createSpeechRecognizer(context)
      } catch (e: Exception) {
        Log.w(TAG, "Could not create a speech recognizer", e)
        null
      }
    if (created == null) {
      finish("")
      return
    }
    recognizer = created

    created.setRecognitionListener(
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
          onPartial(firstResult(partialResults))
        }

        override fun onResults(results: Bundle?) {
          finish(firstResult(results))
        }

        override fun onError(error: Int) {
          Log.d(TAG, "Speech recognition ended with error $error")
          finish("")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
    )

    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Shown to the user by some recognizers, and it identifies the caller to all of them.
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      }

    try {
      created.startListening(intent)
    } catch (e: Exception) {
      Log.w(TAG, "The recognizer refused to start listening", e)
      finish("")
    }
  }

  /** Closes the microphone without reporting a result. */
  fun cancel() {
    val current = recognizer ?: return
    recognizer = null
    try {
      current.cancel()
      current.destroy()
    } catch (e: Exception) {
      Log.d(TAG, "Recognizer was already gone", e)
    }
  }

  private fun firstResult(bundle: Bundle?): String =
    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
}
