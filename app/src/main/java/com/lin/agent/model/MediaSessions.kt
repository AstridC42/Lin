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

package com.lin.agent.model

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.lin.agent.core.media.MediaCommand
import com.lin.agent.core.media.Session
import com.lin.agent.notifications.LinNotificationListener

/**
 * What the phone is playing, for the two tools that need to know.
 *
 * Shared because asking after doing something is the honest half of both: [MediaTool] reads a
 * session to say what is on, and [PlayMusicTool] reads one afterwards to find out whether the
 * app it just asked actually started playing - rather than reporting that it did because the
 * intent did not throw.
 *
 * Null, not empty, when notification access is off. The two are different answers - "nothing
 * is playing" and "Lin cannot see" - and collapsing them is how an assistant comes to state
 * the first when the second is true.
 */
fun activeControllers(context: Context): List<MediaController>? =
  runCatching {
      val manager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
          ?: return null
      manager.getActiveSessions(ComponentName(context, LinNotificationListener::class.java))
    }
    .onFailure { Log.i("LinMediaSessions", "No access to media sessions", it) }
    .getOrNull()

/** The parts of a controller the pure code reasons about. */
fun MediaController.asSession(context: Context): Session {
  val state = playbackState
  val actions = state?.actions ?: 0L
  return Session(
    app = appLabel(context, packageName),
    title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
    artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
    playing = state?.state == PlaybackState.STATE_PLAYING,
    // Ordering only. The last position update is the best "when was this last touched" the
    // platform offers without holding state of our own.
    activeAt = state?.lastPositionUpdateTime ?: 0L,
    can =
      buildSet {
        if (actions and PlaybackState.ACTION_PLAY != 0L) add(MediaCommand.PLAY)
        if (actions and PlaybackState.ACTION_PLAY_PAUSE != 0L) {
          add(MediaCommand.PLAY)
          add(MediaCommand.PAUSE)
        }
        if (actions and PlaybackState.ACTION_PAUSE != 0L) add(MediaCommand.PAUSE)
        if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add(MediaCommand.NEXT)
        if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add(MediaCommand.PREVIOUS)
      },
  )
}

/** The app's own name, falling back to its package when it will not give one. */
fun appLabel(context: Context, packageName: String): String =
  runCatching {
      val info = context.packageManager.getApplicationInfo(packageName, 0)
      context.packageManager.getApplicationLabel(info).toString()
    }
    .getOrDefault(packageName)
