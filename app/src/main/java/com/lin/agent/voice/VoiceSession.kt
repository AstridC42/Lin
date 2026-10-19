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
import android.os.IBinder
import android.util.Log
import com.lin.agent.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps a voice conversation going after Lin leaves the screen: another app on top, or the screen
 * locked.
 *
 * Android lets an app use the microphone in the background only from a foreground service that
 * was started while the app was on screen. So this starts with the voice session - the moment
 * somebody taps the microphone - and stays up, with its notification, until the conversation
 * stops: nothing more was heard, the answer finished without listening again, or Stop was tapped.
 * The conversation itself still runs where it always did; this only keeps it allowed and alive.
 */
class VoiceSessionService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      VoiceSession.stopRequested()
      stopSelf()
      return START_NOT_STICKY
    }
    runCatching { startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
      .onFailure {
        // Started from the background after all: nothing to keep alive, so nothing to show.
        Log.w(TAG, "Could not keep the voice session in the foreground", it)
        stopSelf()
      }
    return START_NOT_STICKY
  }

  private fun notification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(
      NotificationChannel(CHANNEL, getString(R.string.voice_session_channel), NotificationManager.IMPORTANCE_LOW)
    )
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, Class.forName("com.lin.agent.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val stop =
      PendingIntent.getService(
        this,
        1,
        Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_mic)
      .setContentTitle(getString(R.string.voice_session_title))
      .setContentText(getString(R.string.voice_session_detail))
      .setOngoing(true)
      .setContentIntent(open)
      .addAction(Notification.Action.Builder(null, getString(R.string.voice_session_stop), stop).build())
      .build()
  }

  private companion object {
    const val TAG = "LinVoiceSession"
    const val CHANNEL = "lin-voice-session"
    const val ID = 48
    const val ACTION_STOP = "com.lin.agent.voice.STOP_SESSION"
  }
}

/** Starting and ending the session, and the Stop button's way back to the conversation. */
object VoiceSession {
  private val _stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Emits when Stop is tapped on the notification. */
  val stops: SharedFlow<Unit> = _stops.asSharedFlow()

  @Volatile var active = false
    private set

  fun begin(context: Context) {
    if (active) return
    active = runCatching { context.startForegroundService(Intent(context, VoiceSessionService::class.java)) }.isSuccess
  }

  fun end(context: Context) {
    if (!active) return
    active = false
    runCatching { context.stopService(Intent(context, VoiceSessionService::class.java)) }
  }

  internal fun stopRequested() {
    active = false
    _stops.tryEmit(Unit)
  }
}
