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

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.lin.agent.core.media.describeNowPlaying
import com.lin.agent.core.media.musicRequest
import com.lin.agent.core.media.searchUrlFor
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Plays something in the music app the user chose.
 *
 * Three ways in, tried in order, because no single one works everywhere. Android's
 * `MEDIA_PLAY_FROM_SEARCH` is the right way to ask and some apps play straight away; others
 * declare it and only open a search; others declare nothing. There is no way to ask an app
 * which it is, so the fallback is a search link into the app, and the last resort is opening
 * the app and saying that is all that happened.
 *
 * Then - and this is the part that makes it honest - it looks at what the phone is actually
 * playing a moment later and reports that, rather than reporting the intent it sent. "I played
 * Stan" when the app opened on a search page is exactly the kind of claim this project treats
 * as a bug. That check needs notification access, so without it the answer says what was asked
 * for and that Lin cannot see whether it worked.
 */
class PlayMusicTool(
  private val context: Context,
  private val settings: SettingsRepository,
  private val guard: LaunchGuard,
) : AgentTool {

  override val name = "play_music"

  override val description =
    "Play music in the user's music app: a song, an artist, an album or a playlist."

  override val parameters =
    listOf(
      ToolParameter(
        name = "what",
        type = ParamType.STRING,
        description = "What to play, as the user said it, for example Stan by Eminem. Leave " +
          "it out if they just said to play something, and the music app resumes.",
        required = false,
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val said = (arguments["what"] as? String).orEmpty()
    val request = musicRequest(said)
    // "Play something" names nothing, and a tool that demanded a title for it is what sent a
    // 12B model round in circles on a phone: it could not satisfy the parameter and could not
    // decide to stop. Nothing named means resume whatever was last playing, which is what
    // somebody saying "play something" to a phone that has a paused album actually wants.
    if (request == null) return resume()

    val chosen = settings.musicApp.first()
    if (chosen.isBlank()) {
      return ToolResult.Failed(
        "No music app has been chosen yet. Tell the user to pick one in Lin's settings, under " +
          "Music - Lin will not guess which service to open."
      )
    }
    if (!isInstalled(chosen)) {
      return ToolResult.Failed(
        "The music app Lin was told to use is not installed any more. Tell the user to choose " +
          "another one in settings."
      )
    }

    val app = appLabel(context, chosen)
    if (!guard.allow("play-$chosen", System.currentTimeMillis())) {
      return ToolResult.Ok("Done - $app is already starting that.")
    }

    // The app's own session first, before any intent. This is how a car asks a music app to
    // play something - the same call Android Auto makes - and it is the only one that
    // actually starts playback rather than opening a search page. It also means nothing comes
    // to the foreground: the music just starts, which is what was asked for.
    val throughSession = playThroughSession(chosen, request.query, request.title, request.artist)
    if (!throughSession) {
      // No session to ask, or it would not take it. Open the app, which gives it one.
      val asked = ask(chosen, request.query, request.title, request.artist)
      if (!asked) return ToolResult.Failed("$app would not take the request.")

      // Opening is not playing. Wait for the app to publish a session and ask it properly,
      // because an app opened on a search page sits there until somebody taps.
      waitForSession(chosen)?.let { late ->
        runCatching {
          late.transportControls.playFromSearch(request.query, searchExtras(request.title, request.artist))
        }
      }
    }

    // A moment to start, then what is really playing. Long enough for a local library, not
    // long enough to be a wait: if a stream is still buffering the answer says it was asked
    // for rather than claiming it is playing.
    delay(LOOK_AFTER_MS)

    val controllers = activeControllers(context)
      ?: return ToolResult.Ok(
        "$app was asked to play ${request.query}. Lin cannot see whether it started - that " +
          "needs notification access, which the user turns on in settings. Say what was asked " +
          "for, not that it is playing."
      )

    val playing = controllers.map { it.asSession(context) }.firstOrNull { it.playing }
    return if (playing != null && playing.app == app) {
      ToolResult.Ok("Done - ${describeNowPlaying(listOf(playing))}")
    } else {
      ToolResult.Ok(
        "$app was asked for ${request.query} and is open, but nothing is playing yet. Tell the " +
          "user that rather than saying it is playing."
      )
    }
  }

  /**
   * Asks an app that is already running to search and play, through its media session.
   *
   * `playFromSearch` is the call a car makes: a media app that supports voice in a car
   * implements it, and most streaming apps do, because Android Auto requires it of them. An
   * app that does not implement it ignores the call silently - which is why the caller still
   * checks afterwards what is actually playing rather than trusting this.
   *
   * False when there is nothing to ask: no notification access, or no session for that app.
   */
  private fun playThroughSession(
    packageName: String,
    query: String,
    title: String,
    artist: String,
  ): Boolean {
    val controller =
      activeControllers(context)?.firstOrNull { it.packageName == packageName } ?: return false
    return runCatching {
        controller.transportControls.playFromSearch(query, searchExtras(title, artist))
        true
      }
      .getOrDefault(false)
  }

  /** Waits briefly for a just-opened app to publish a session, or gives up. */
  private suspend fun waitForSession(packageName: String): MediaController? {
    repeat(WAIT_TRIES) {
      delay(WAIT_STEP_MS)
      activeControllers(context)?.firstOrNull { it.packageName == packageName }?.let {
        return it
      }
    }
    return null
  }

  /**
   * The parts of the request, for an app that can use them.
   *
   * The same extras Android Auto passes, so an app that handles a car's "play Stan by Eminem"
   * handles this identically. Only sent when the request actually said which was which.
   */
  private fun searchExtras(title: String, artist: String): Bundle =
    Bundle().apply {
      if (title.isNotBlank() && artist.isNotBlank()) {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        putString(MediaStore.EXTRA_MEDIA_TITLE, title)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
      }
    }

  /** The three ways in, in order of how well they work. Returns whether any of them started. */
  private fun ask(packageName: String, query: String, title: String, artist: String): Boolean {
    val playFromSearch =
      Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(SearchManager.QUERY, query)
        // The focus extra tells the app what kind of thing this is. Given the pair, it is a
        // song; given one word, it is whatever the app decides, which is the right answer
        // when nobody said.
        if (title.isNotBlank() && artist.isNotBlank()) {
          putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
          putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
          putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        }
      }
    if (start(playFromSearch)) return true

    searchUrlFor(packageName, query)?.let { url ->
      val link =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
          setPackage(packageName)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      if (start(link)) return true
    }

    val launch =
      context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return launch != null && start(launch)
  }

  /**
   * Started, and false if the app does not handle it.
   *
   * Tried rather than resolved first: whether an app answers play-from-search is the whole
   * question here, package visibility can hide an answer that would have worked, and
   * startActivity says no by throwing - which is what the next way in is for.
   */
  private fun start(intent: Intent): Boolean =
    runCatching { context.startActivity(intent) }.isSuccess

  /**
   * Starts whatever was last playing, or says there is nothing to start.
   *
   * Through the media session rather than by opening the app, for the same reason the
   * search path prefers it: it actually begins playback instead of putting a screen in
   * front of somebody who asked for music.
   */
  private fun resume(): ToolResult {
    val controller =
      activeControllers(context).orEmpty().firstOrNull()
        ?: return ToolResult.Failed(
          "Nothing is loaded in any music app, so there is nothing to resume. Ask the user " +
            "what they would like to hear."
        )
    return runCatching {
        controller.transportControls.play()
        ToolResult.Ok("Done - ${appLabel(context, controller.packageName)} is playing again.")
      }
      .getOrElse { ToolResult.Failed("The music app would not start playing.") }
  }

  private fun isInstalled(packageName: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

  private companion object {
    const val LOOK_AFTER_MS = 1_500L

    /** Up to about three seconds for a cold app to publish a session. */
    const val WAIT_TRIES = 6
    const val WAIT_STEP_MS = 500L
  }
}
