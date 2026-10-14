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
import android.net.Uri
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
        description = "What to play, as the user said it, for example \"Stan by Eminem\".",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val request =
      musicRequest((arguments["what"] as? String).orEmpty())
        ?: return ToolResult.Failed("Nothing was named to play.")

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

    val asked = ask(chosen, request.query, request.title, request.artist)
    if (!asked) return ToolResult.Failed("$app would not take the request.")

    // A moment for the app to start, then what is really playing. Long enough for a local
    // library, not long enough to be a wait: if a stream is still buffering the answer says
    // it was asked for rather than claiming it is playing.
    delay(LOOK_AFTER_MS)

    val controllers = activeControllers(context)
      ?: return ToolResult.Ok(
        "$app was asked to play ${request.query}. Lin cannot see whether it started - that " +
          "needs notification access. Say what was asked for, not that it is playing."
      )

    val playing = controllers.map { it.asSession(context) }.firstOrNull { it.playing }
    return if (playing != null && playing.app == app) {
      ToolResult.Ok("Done - ${describeNowPlaying(listOf(playing))}")
    } else {
      ToolResult.Ok(
        "$app was opened with a search for ${request.query}, and nothing is playing yet. Tell " +
          "the user it is open on the search rather than saying it is playing."
      )
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

  private fun isInstalled(packageName: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

  private companion object {
    const val LOOK_AFTER_MS = 1_500L
  }
}
