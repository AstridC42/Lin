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

package com.google.ai.edge.gallery.tools

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.google.ai.edge.gallery.notifications.StarNotificationListener
import com.google.ai.edge.gallery.media.MusicApps
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGMediaControlTool"

/**
 * Play, pause, skip, and what is playing.
 *
 * Whatever app currently owns audio is controlled through its `MediaSession`, so this works with
 * Spotify, YouTube, a podcast player or the radio without knowing anything about them.
 *
 * Reaching those sessions needs `MediaSessionManager.getActiveSessions`, which Android gates behind
 * *notification access* - the same grant the notification tools need, for a different reason. One
 * grant covers both, which is worth saying to the user rather than asking them twice.
 */
class MediaControlTool(
  private val context: Context,
  /** The app music is sent to, read fresh so a change in settings applies without a restart. */
  private val preferredMusicApp: () -> String = { "" },
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Start playing music by name - a song, artist, album or playlist. Use for \"play ...\". " +
        "For pausing or skipping what is already playing, use controlMedia."
  )
  fun playMusic(
    @ToolParam(description = "What to play, for example 'Radiohead' or 'my liked songs'.")
    query: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (query.isBlank()) {
        return@runBlocking mapOf(
          "status" to "failed",
          "error" to "Nothing was named to play. Ask the user what they want to hear.",
        )
      }
      val intent =
        MusicApps.playIntent(context, query.trim(), preferredMusicApp())
          ?: return@runBlocking mapOf(
            "status" to "failed",
            "error" to
              "No app on this phone can play music by name. Tell the user to install a music app.",
          )
      try {
        context.startActivity(intent)
      } catch (e: Exception) {
        Log.w(TAG, "Could not start playback", e)
        return@runBlocking mapOf(
          "status" to "failed",
          "error" to "The music app would not open.",
        )
      }
      sendProgress("Playing \"$query\"", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "playing" to query.trim(),
        "hint" to
          "The music app was asked to play this. Say one short line; do not claim to know the " +
            "track that started.",
      )
    }
  }

  @Tool(
    description =
      "Control whatever is playing. Actions: play, pause, toggle, next, previous, stop."
  )
  fun controlMedia(
    @ToolParam(description = "One of: play, pause, toggle, next, previous, stop.") action: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val controller =
        activeController()
          ?: return@runBlocking noSessionFailure(
            "Nothing is playing at the moment, so there is nothing to $action."
          )

      val controls = controller.transportControls
      when (action.trim().lowercase()) {
        "play", "resume" -> controls.play()
        "pause" -> controls.pause()
        "toggle", "playpause", "play_pause" ->
          if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controls.pause()
          } else {
            controls.play()
          }
        "next", "skip" -> controls.skipToNext()
        "previous", "prev", "back" -> controls.skipToPrevious()
        "stop" -> controls.stop()
        else ->
          return@runBlocking failure(
            "\"$action\" is not a media action. Use play, pause, toggle, next, previous or stop."
          )
      }
      sendProgress("Media: $action", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "action" to action,
        "app" to appLabel(controller.packageName),
      )
    }
  }

  @Tool(
    description =
      "What is playing now - track, artist, app."
  )
  fun nowPlaying(): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val controller =
        activeController()
          ?: return@runBlocking noSessionFailure("Nothing is playing at the moment.")

      val metadata = controller.metadata
      val title =
        metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
      val artist =
        metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
          ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
      val album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
      val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING

      if (title.isBlank() && artist.isBlank()) {
        return@runBlocking mapOf(
          "status" to "succeeded",
          "app" to appLabel(controller.packageName),
          "playing" to playing,
          "hint" to "Something is open in ${appLabel(controller.packageName)} but it reports no title.",
        )
      }
      mapOf(
        "status" to "succeeded",
        "title" to title,
        "artist" to artist,
        "album" to album,
        "app" to appLabel(controller.packageName),
        "playing" to playing,
      )
    }
  }

  @Tool(description = "Change the phone's media volume, or mute it.")
  fun setMediaVolume(
    @ToolParam(description = "Volume from 0 (muted) to 100 (loudest).") percent: Int
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
          ?: return@runBlocking failure("The volume cannot be changed on this device.")
      val clamped = percent.coerceIn(0, 100)
      val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      // Rounded rather than truncated so "50%" lands on the middle step rather than one below it.
      val target = Math.round(max * clamped / 100f)
      return@runBlocking try {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        sendProgress("Volume $clamped%", inProgress = false)
        mapOf("status" to "succeeded", "volume_percent" to clamped)
      } catch (e: SecurityException) {
        // Setting the volume to or from zero is blocked while Do Not Disturb is on unless the app
        // holds notification policy access, which is a heavier grant than this is worth.
        Log.w(TAG, "Volume change refused", e)
        failure(
          "The volume could not be changed, most likely because Do Not Disturb is on. Tell the " +
            "user rather than trying again."
        )
      }
    }
  }

  /**
   * The session worth controlling: the one actually playing, or failing that the most recent.
   *
   * Several apps can hold a session at once - a paused podcast, a video that has ended, a game.
   * Taking the first would pause the wrong thing, so an actively playing session always wins.
   */
  private fun activeController(): MediaController? {
    if (!StarNotificationListener.isGranted(context)) return null
    val manager =
      context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
    val component = ComponentName(context, StarNotificationListener::class.java)
    val sessions =
      try {
        manager.getActiveSessions(component)
      } catch (e: SecurityException) {
        Log.w(TAG, "Media sessions are not readable without notification access", e)
        return null
      }
    if (sessions.isNullOrEmpty()) return null
    return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
      ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
      ?: sessions.first()
  }

  /** Distinguishes "no permission" from "nothing playing", which are not the same answer. */
  private fun noSessionFailure(nothingPlaying: String): Map<String, Any> =
    if (!StarNotificationListener.isGranted(context)) {
      failure(
        "Media control needs notification access, which has not been granted. Tell the user to " +
          "turn on Star Storyteller under Settings > Notifications > Device & app notifications, " +
          "or from the Guide panel. Do not claim nothing is playing."
      )
    } else {
      failure(nothingPlaying)
    }

  private fun appLabel(packageName: String?): String {
    if (packageName.isNullOrEmpty()) return "an app"
    return try {
      val info = context.packageManager.getApplicationInfo(packageName, 0)
      context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
      packageName
    }
  }

  private suspend fun sendProgress(label: String, inProgress: Boolean) {
    executionContext
      ?.actionChannel
      ?.send(SkillProgressToolAction(label = label, inProgress = inProgress))
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)
}
