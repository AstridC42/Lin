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
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/** What the microphone produced. */
sealed interface Heard {
  /** Words so far, which may still change. Shown while speaking so it does not look frozen. */
  data class Partial(val text: String) : Heard

  data class Final(val text: String) : Heard

  data class Failed(val message: String) : Heard
}

/**
 * Speech to text, through whatever recogniser the phone has.
 *
 * On-device is asked for where the platform supports it. That is a privacy choice rather than a
 * performance one: the rest of this app is built so that nothing said to it leaves the phone
 * unless the user pointed it at their own server, and routing dictation through a cloud
 * recogniser would quietly undo that for every spoken word. Where no on-device recogniser
 * exists the platform falls back on its own, which is worth knowing about and is why the
 * setting screen says so rather than claiming more than it can.
 */
class Dictation(
  private val context: Context,
  /** Lin's own hearing, used instead of the phone's whenever it is on and downloaded. */
  private val ears: LinEars,
) {

  fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

  /**
   * Lin's own hearing when it is on and downloaded; the phone's speech recognition otherwise,
   * until it is.
   */
  fun listen(): Flow<Heard> = kotlinx.coroutines.flow.flow {
    val own = runCatching { ears.chosen() }.getOrNull()
    if (own != null) emitAll(ears.listen(own)) else emitAll(listenWithPhone())
  }

  /**
   * Listens until the speaker stops, emitting words as they are recognised.
   *
   * A callbackFlow so that collecting stops the microphone: cancelling the collector closes the
   * recogniser, which is the behaviour the UI needs when somebody navigates away mid-sentence
   * and the behaviour a microphone must have regardless.
   */
  private fun listenWithPhone(): Flow<Heard> {
    if (!available()) {
      return flow { emit(Heard.Failed("This phone has no speech recogniser available.")) }
    }

    return callbackFlow {
      val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
      val listener =
        object : RecognitionListener {
          override fun onResults(results: Bundle?) {
            val text = firstOf(results)
            if (text.isEmpty()) trySend(Heard.Failed("Nothing was heard."))
            else trySend(Heard.Final(text))
            channel.close()
          }

          override fun onPartialResults(partial: Bundle?) {
            firstOf(partial).takeIf { it.isNotEmpty() }?.let { trySend(Heard.Partial(it)) }
          }

          override fun onError(error: Int) {
            trySend(Heard.Failed(describe(error)))
            channel.close()
          }

          override fun onReadyForSpeech(params: Bundle?) = Unit

          override fun onBeginningOfSpeech() = Unit

          override fun onRmsChanged(rms: Float) = Unit

          override fun onBufferReceived(buffer: ByteArray?) = Unit

          override fun onEndOfSpeech() = Unit

          override fun onEvent(type: Int, params: Bundle?) = Unit
        }

      recognizer.setRecognitionListener(listener)
      val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
          )
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Preferred, not required: asking for it where it exists keeps spoken words on the
            // phone, and where it does not the platform still answers.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          }
        }

      runCatching { recognizer.startListening(intent) }
        .onFailure {
          Log.w(TAG, "Could not start listening", it)
          trySend(Heard.Failed("The microphone could not be started."))
          channel.close()
        }

      awaitClose {
        // The collector went away - the user navigated off, or sent the message. Releasing the
        // recogniser is what actually closes the microphone, and leaving it open because a
        // screen changed is not a thing this app is going to do.
        runCatching {
          recognizer.stopListening()
          recognizer.destroy()
        }
      }
    }
  }

  private fun firstOf(bundle: Bundle?): String =
    bundle
      ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      ?.firstOrNull()
      ?.trim()
      .orEmpty()

  /** The recogniser's numeric codes, as something a person can act on. */
  private fun describe(error: Int): String =
    when (error) {
      SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        "Lin does not have permission to use the microphone."
      SpeechRecognizer.ERROR_NETWORK,
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "The speech recogniser needed the network and could not reach it."
      SpeechRecognizer.ERROR_NO_MATCH -> "That was not understood. Try again."
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was said."
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy."
      else -> "Listening failed."
    }

  private companion object {
    const val TAG = "LinDictation"
  }
}
