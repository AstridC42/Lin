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

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.util.Log
import com.lin.agent.core.media.MediaCommand
import com.lin.agent.core.media.canDo
import com.lin.agent.core.media.chooseSession
import com.lin.agent.core.media.describeNowPlaying
import com.lin.agent.core.media.volumeAfterStep
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult

/**
 * Whatever is playing on the phone.
 *
 * Not staged, and that is the point of having a rule rather than a reflex: pausing music is
 * seen the instant it happens and undone by saying so. The staged-action rule exists for
 * things that reach other people or cannot be taken back, and this is neither.
 *
 * Volume is the exception, and only upwards. Headphones at full on a misheard "turn it up" is
 * an injury rather than an annoyance, so a step is a step and a run of them stops below
 * maximum - see [volumeAfterStep].
 *
 * Reaching the sessions at all needs notification access, which is the same grant that lets
 * Lin answer messages: the alternative, MEDIA_CONTENT_CONTROL, is for system apps. So this
 * works exactly when that is on, and says so when it is not.
 */
class MediaTool(private val context: Context) : AgentTool {

  override val name = "media"

  override val description =
    "See or control what is playing on the phone: what it is, pause, play, skip, go back, or " +
      "make it louder or quieter."

  override val parameters =
    listOf(
      ToolParameter(
        name = "action",
        type = ParamType.STRING,
        description =
          "One of: what, play, pause, next, previous, louder, quieter. Use what to just look.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val action = (arguments["action"] as? String)?.trim()?.lowercase().orEmpty().ifEmpty { "what" }

    if (action == "louder" || action == "quieter") return volume(up = action == "louder")

    val controllers =
      activeControllers(context)
        ?: return ToolResult.Failed(
          "Lin cannot see what is playing: that needs notification access, which the user turns " +
            "on in settings. Tell them rather than guessing."
        )

    // Paired once and kept paired. Reading a controller twice gives two Sessions that are
    // equal only if nothing moved in between - and one of the fields is the session's last
    // position update, which moves constantly while music plays. Looking the controller back
    // up by value therefore failed exactly when something was playing, which is the only time
    // anybody asks Lin to pause it: "whatever was playing has gone", with the music still on.
    val paired = controllers.map { it to it.asSession(context) }
    val sessions = paired.map { it.second }
    if (action == "what") return ToolResult.Ok(describeNowPlaying(sessions))

    val command =
      when (action) {
        "play", "resume" -> MediaCommand.PLAY
        "pause", "stop" -> MediaCommand.PAUSE
        "next", "skip" -> MediaCommand.NEXT
        "previous", "back" -> MediaCommand.PREVIOUS
        else ->
          return ToolResult.Failed(
            "'$action' is not something this can do. Use what, play, pause, next, previous, " +
              "louder or quieter."
          )
      }

    val chosen =
      chooseSession(sessions)
        ?: return ToolResult.Failed("Nothing is playing, and nothing is paused to start again.")
    // By identity: chooseSession returns one of the objects it was given, so this is the
    // controller that session was read from, whatever has changed since.
    val controller =
      paired.firstOrNull { it.second === chosen }?.first
        ?: return ToolResult.Failed("Whatever was playing has gone.")

    // Asked rather than attempted: a transport control that goes nowhere is silent, and the
    // model would report a skip that never happened.
    if (!canDo(chosen, command)) {
      return ToolResult.Failed("${chosen.app} cannot do that from here.")
    }

    return try {
      when (command) {
        MediaCommand.PLAY -> controller.transportControls.play()
        MediaCommand.PAUSE -> controller.transportControls.pause()
        MediaCommand.NEXT -> controller.transportControls.skipToNext()
        MediaCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
      }
      ToolResult.Ok("Done, in ${chosen.app}.")
    } catch (e: Throwable) {
      Log.w(TAG, "Media command failed", e)
      ToolResult.Failed("${chosen.app} did not take that.")
    }
  }

  private fun volume(up: Boolean): ToolResult {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      ?: return ToolResult.Failed("The volume cannot be reached on this phone.")
    return try {
      val stream = AudioManager.STREAM_MUSIC
      val max = audio.getStreamMaxVolume(stream)
      val now = audio.getStreamVolume(stream)
      val wanted = volumeAfterStep(now, max, up)
      if (wanted == now) {
        val why =
          if (up) "It is as loud as Lin will set it - the rest is by hand."
          else "It is already silent."
        return ToolResult.Ok(why)
      }
      audio.setStreamVolume(stream, wanted, 0)
      ToolResult.Ok(if (up) "Louder." else "Quieter.")
    } catch (e: SecurityException) {
      // Do Not Disturb takes volume changes away without warning.
      ToolResult.Failed("The phone would not allow a volume change just now.")
    }
  }

  private companion object {
    const val TAG = "LinMediaTool"
  }
}
