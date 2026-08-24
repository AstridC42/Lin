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

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R

private const val TAG = "AGWakeWord"
private const val CHANNEL_ID = "star_wake_word"
private const val NOTIFICATION_ID = 4711

/** How long to wait before restarting the recognizer after it stops or errors. */
private const val RESTART_DELAY_MS = 800L

/** A longer pause after an error that means the recognizer is unavailable right now. */
private const val BUSY_RESTART_DELAY_MS = 5_000L

/** The phrase used when the user has not chosen one. */
const val DEFAULT_WAKE_PHRASE = "hey star"

/**
 * Listens continuously for a spoken phrase and opens the assistant when it hears it.
 *
 * ### Why this is a service and not a wake word
 *
 * Android has no API that lets an ordinary app add an always-on wake word. The low-power path,
 * `AlwaysOnHotwordDetector`, only recognises keyphrases that the device's audio DSP was built and
 * enrolled with, which in practice means the manufacturer's own phrase. Everything else has to
 * keep the microphone open and run ordinary speech recognition over it.
 *
 * That is what this does, and it is an honest trade rather than a hidden one: the microphone is
 * held for as long as the service runs, it costs noticeably more battery than a real hardware
 * hotword, and Android shows the microphone indicator throughout. It is off by default, has to be
 * turned on deliberately, and posts an ongoing notification with a Stop button for as long as it
 * is listening - not because a foreground service requires one, though it does, but because
 * something recording continuously should never be doing so invisibly.
 *
 * ### Restarting
 *
 * `SpeechRecognizer` is built around one utterance: it stops on silence, and it stops on error.
 * Continuous listening therefore means restarting it every time it finishes, which is what most of
 * this class is. Restarts are delayed rather than immediate - a tight restart loop against a
 * recognizer that is refusing to start would spin the CPU without ever listening.
 */
class WakeWordService : Service(), RecognitionListener {

  private var recognizer: SpeechRecognizer? = null
  private val handler = Handler(Looper.getMainLooper())
  private var running = false
  private var phrase = DEFAULT_WAKE_PHRASE

  private val restart = Runnable { startListening() }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }

    phrase = intent?.getStringExtra(EXTRA_PHRASE)?.trim()?.lowercase()?.ifEmpty { null }
      ?: DEFAULT_WAKE_PHRASE

    if (!hasMicrophonePermission()) {
      Log.w(TAG, "Started without RECORD_AUDIO; stopping")
      stopSelf()
      return START_NOT_STICKY
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      Log.w(TAG, "No speech recognition on this device; stopping")
      stopSelf()
      return START_NOT_STICKY
    }

    startInForeground()
    if (!running) {
      running = true
      startListening()
    }
    // Restarted by the system if it kills us: the point of the feature is to be listening.
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    handler.removeCallbacks(restart)
    runCatching { recognizer?.destroy() }
    recognizer = null
    super.onDestroy()
  }

  private fun startInForeground() {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
      manager?.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_word_channel_name),
            // Low, not default: this notification is a disclosure that has to stay on screen, not
            // an event worth interrupting anyone for.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = getString(R.string.wake_word_channel_description) }
      )
    }

    val stopIntent =
      PendingIntent.getService(
        this,
        0,
        Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val openIntent =
      PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.wake_word_notification_title, phrase))
        .setContentText(getString(R.string.wake_word_notification_text))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(openIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(0, getString(R.string.wake_word_stop), stopIntent)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun startListening() {
    if (!running) return
    if (!hasMicrophonePermission()) {
      // Permission can be revoked while the service runs, which would otherwise leave it looping
      // against a recognizer that can never succeed.
      Log.w(TAG, "Microphone permission went away; stopping")
      stopSelf()
      return
    }
    try {
      recognizer?.destroy()
      val created = SpeechRecognizer.createSpeechRecognizer(this)
      created.setRecognitionListener(this)
      recognizer = created
      created.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Could not start listening", e)
      scheduleRestart(BUSY_RESTART_DELAY_MS)
    }
  }

  private fun scheduleRestart(delayMs: Long) {
    if (!running) return
    handler.removeCallbacks(restart)
    handler.postDelayed(restart, delayMs)
  }

  private fun hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  /** Opens the assistant and hands it the microphone. */
  private fun wake() {
    Log.d(TAG, "Wake phrase heard")
    val intent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action = Intent.ACTION_ASSIST
        putExtra(EXTRA_START_LISTENING, true)
      }
    try {
      startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Could not open the assistant", e)
    }
  }

  // ---- RecognitionListener ------------------------------------------------------------------

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {}

  override fun onRmsChanged(rmsdB: Float) {}

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {}

  override fun onError(error: Int) {
    // NO_MATCH and SPEECH_TIMEOUT are the ordinary outcome of a quiet room, not a problem.
    val delay =
      when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_DELAY_MS
        else -> BUSY_RESTART_DELAY_MS
      }
    scheduleRestart(delay)
  }

  override fun onResults(results: Bundle?) {
    handleHeard(results)
    scheduleRestart(RESTART_DELAY_MS)
  }

  override fun onPartialResults(partialResults: Bundle?) {
    // Acting on partials is what makes this feel responsive: waiting for the recognizer to decide
    // the utterance is over adds a second or more between the phrase and the assistant opening.
    if (handleHeard(partialResults)) {
      runCatching { recognizer?.cancel() }
      scheduleRestart(RESTART_DELAY_MS)
    }
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}

  /** Returns true when the wake phrase was in [results]. */
  private fun handleHeard(results: Bundle?): Boolean {
    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
    if (heard.none { containsWakePhrase(it, phrase) }) return false
    wake()
    return true
  }

  companion object {
    const val ACTION_STOP = "com.starstoryteller.assistant.STOP_WAKE_WORD"
    const val EXTRA_PHRASE = "com.starstoryteller.assistant.WAKE_PHRASE"

    /**
     * Whether a transcript contains the wake phrase.
     *
     * Matched on word boundaries rather than as a bare substring: "hey star" must not fire on
     * "hey started" or "they starve". Punctuation and casing vary between recognizers and between
     * utterances, so both sides are reduced to lowercase words before comparing.
     */
    internal fun containsWakePhrase(transcript: String, phrase: String): Boolean {
      val words = transcript.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
      val target = phrase.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
      if (target.isEmpty() || words.size < target.size) return false
      return (0..words.size - target.size).any { start ->
        target.indices.all { words[start + it] == target[it] }
      }
    }

    /** Starts listening, if the user has turned it on. */
    fun start(context: Context, phrase: String) {
      val intent =
        Intent(context, WakeWordService::class.java).putExtra(EXTRA_PHRASE, phrase)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, WakeWordService::class.java))
    }
  }
}
