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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.lin.agent.AssistActivity
import com.lin.agent.MainActivity
import com.lin.agent.R
import com.lin.agent.core.voice.WakeWord

/**
 * Listens for "Hey Lin" while the app is not open.
 *
 * A foreground service with a permanent notification, which is not a formality: this holds the
 * microphone open, and an app that listens without something on screen saying so is doing the
 * thing people are right to be afraid of. The notification is the honest part of the feature.
 *
 * **What this is not.** A wake-word engine listens to audio at almost no cost. This runs the
 * speech recogniser in a loop and reads the words, because no pre-trained wake-word model
 * exists for "Hey Lin" - the open ones ship "Hey Jarvis" and "Okay Nabu", and a custom one
 * means an account with somebody's cloud. So the battery cost is real, the setting says so,
 * and it is off until turned on. The matcher is kept apart in [WakeWord] so a proper model can
 * replace the listening half without touching the rest.
 *
 * The recogniser is asked for on-device where the platform has one, for the same reason
 * dictation is: nothing said to this app should leave the phone unless the user pointed it at
 * their own server.
 */
class WakeService : Service() {

  private var recognizer: SpeechRecognizer? = null
  private var stopping = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!goForeground()) {
      stopSelf()
      return START_NOT_STICKY
    }
    startListening()
    // Restarted if the system kills it, because a wake word that quietly stops working is
    // worse than one that was never turned on.
    return START_STICKY
  }

  override fun onDestroy() {
    stopping = true
    runCatching {
      recognizer?.stopListening()
      recognizer?.destroy()
    }
    recognizer = null
    super.onDestroy()
  }

  private fun goForeground(): Boolean =
    runCatching {
        ensureChannel()
        val open =
          PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
          )
        val notification =
          Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.wake_listening))
            .setContentText(getString(R.string.wake_listening_detail))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
          startForeground(ID, notification)
        }
        true
      }
      .onFailure { Log.w(TAG, "Could not start listening in the foreground", it) }
      .getOrDefault(false)

  /**
   * One round of listening, restarting itself.
   *
   * The recogniser ends after every utterance and after every silence, so continuous listening
   * is a loop of short sessions. Restarted from the callbacks rather than on a timer, because
   * starting a session while one is running is what makes the recogniser stop answering at
   * all.
   */
  private fun startListening() {
    if (stopping) return
    runCatching {
        recognizer?.destroy()
        val speech =
          if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
              SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
          ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
          } else {
            SpeechRecognizer.createSpeechRecognizer(this)
          }
        recognizer = speech
        speech.setRecognitionListener(Listener())
        speech.startListening(
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          }
        )
      }
      .onFailure {
        Log.w(TAG, "Could not start the recogniser", it)
        stopSelf()
      }
  }

  /**
   * Brings up the panel, listening, with whatever was said after the phrase.
   *
   * The panel rather than the whole app: "Hey Lin, what's the time" should not take over the
   * screen of whatever somebody was doing when they said it. See [com.lin.agent.AssistActivity].
   *
   * A token per wake, so that two wakes are two summons even when the same words were said
   * both times - and so that the panel being recreated mid-answer does not ask again. See
   * [com.lin.agent.core.voice.Summon].
   */
  private fun wake(heard: String) {
    val question = WakeWord.afterWake(heard)
    startActivity(
      Intent(this, AssistActivity::class.java).apply {
        action = ACTION_WOKEN
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(EXTRA_TOKEN, java.util.UUID.randomUUID().toString())
        if (question.isNotBlank()) putExtra(EXTRA_SAID, question)
      }
    )
  }

  private inner class Listener : RecognitionListener {
    override fun onResults(results: Bundle?) {
      val heard =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
      if (WakeWord.saidIn(heard)) wake(heard) else startListening()
    }

    /**
     * Woken on a partial, which is what makes it feel immediate.
     *
     * Waiting for the final result means waiting out the silence that ends an utterance -
     * close to a second of somebody having said the phrase and nothing happening.
     */
    override fun onPartialResults(partial: Bundle?) {
      val heard =
        partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
      if (WakeWord.saidIn(heard)) wake(heard)
    }

    override fun onError(error: Int) {
      // A timeout with nothing said is the ordinary case, not a failure. The others are
      // treated the same way: start again, because the alternative is silently deaf.
      if (!stopping) startListening()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rms: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onEvent(type: Int, params: Bundle?) = Unit
  }

  private fun ensureChannel() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW)
    )
  }

  companion object {
    private const val TAG = "LinWakeService"
    private const val CHANNEL = "lin-listening"
    private const val ID = 42

    /** How the activity knows it was opened by the wake word rather than by a tap. */
    const val ACTION_WOKEN = "com.lin.agent.action.WOKEN"

    /** Whatever was said after "Hey Lin", so it does not have to be said twice. */
    const val EXTRA_SAID = "com.lin.agent.extra.SAID"

    /** Which wake this is, so the question goes exactly once. See Summon. */
    const val EXTRA_TOKEN = "com.lin.agent.extra.TOKEN"

    fun start(context: Context) {
      runCatching {
        context.startForegroundService(Intent(context, WakeService::class.java))
      }
    }

    fun stop(context: Context) {
      runCatching { context.stopService(Intent(context, WakeService::class.java)) }
    }
  }
}
