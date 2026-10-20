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

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.lin.agent.core.phone.RingerMode
import com.lin.agent.core.phone.SoundStream
import com.lin.agent.core.phone.VolumeChange
import com.lin.agent.core.phone.percentOf
import com.lin.agent.core.phone.ringerModeNamed
import com.lin.agent.core.phone.streamNamed
import com.lin.agent.core.phone.volumeChangeFrom
import com.lin.agent.core.phone.volumeIndexFor
import com.lin.agent.core.privileged.ShellCommand
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.privileged.Privileged
import kotlinx.coroutines.delay

private fun audioOf(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

private fun policyAccess(context: Context): Boolean =
  runCatching {
      context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    }
    .getOrDefault(false)

/** Opens the Do Not Disturb access screen once, for the cases Android guards with it. */
private fun askForPolicyAccess(context: Context, guard: LaunchGuard, what: String): ToolResult {
  if (guard.allow("dnd-access", System.currentTimeMillis())) {
    runCatching {
      context.startActivity(
        Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }
  }
  return ToolResult.Ok(
    "Not changed yet: Android only lets Lin $what once it has Do Not Disturb access. The screen " +
      "to allow it is open - tell the user to switch Lin on there and ask again. Do not say it was changed."
  )
}

private fun streamOf(stream: SoundStream) =
  when (stream) {
    SoundStream.MEDIA -> AudioManager.STREAM_MUSIC
    SoundStream.RING -> AudioManager.STREAM_RING
    SoundStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
    SoundStream.ALARM -> AudioManager.STREAM_ALARM
    SoundStream.CALL -> AudioManager.STREAM_VOICE_CALL
  }

private fun ringerOf(audio: AudioManager): RingerMode =
  when (audio.ringerMode) {
    AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
    AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
    else -> RingerMode.SOUND
  }

/**
 * The phone's volumes: media, ringtone, notifications, alarm and calls, set to a level or
 * stepped. Shows the system's own volume bar as it changes, so the user sees what Lin did.
 */
class VolumeTool(private val context: Context, private val guard: LaunchGuard) : AgentTool {

  override val name = "volume"

  override val description =
    "Set or read a phone volume: media (music and videos), ringtone, notification, alarm or call. " +
      "Can set a level, step it up or down, mute or max it."

  override val parameters =
    listOf(
      ToolParameter(
        name = "level",
        type = ParamType.STRING,
        description = "A percentage like 40%, or up, down, mute, max. Leave it out to read the level.",
        required = false,
      ),
      ToolParameter(
        name = "stream",
        type = ParamType.STRING,
        description = "media, ringtone, notification, alarm or call. Media if not said.",
        required = false,
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
    "Use volume for any volume change or question - 'volume 30%', 'turn the ringtone down', " +
      "'max the alarm'. For silent, vibrate or sound mode use sound_mode instead."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val audio = audioOf(context) ?: return ToolResult.Failed("The volume cannot be reached on this phone.")
    val saidStream = arguments["stream"] as? String
    val stream =
      streamNamed(saidStream)
        ?: return ToolResult.Failed("'$saidStream' is not a volume. Use media, ringtone, notification, alarm or call.")
    val type = streamOf(stream)
    val min = runCatching { audio.getStreamMinVolume(type) }.getOrDefault(0)
    val max = audio.getStreamMaxVolume(type)
    val now = audio.getStreamVolume(type)

    val saidLevel = (arguments["level"] as? String)?.trim().orEmpty()
    if (saidLevel.isEmpty() || saidLevel.lowercase() in setOf("what", "read", "check")) {
      val extra =
        if (stream == SoundStream.RING || stream == SoundStream.NOTIFICATION)
          " The phone is in ${ringerOf(audio).label} mode."
        else ""
      return ToolResult.Ok("The ${stream.label} volume is at ${percentOf(now, min, max)}%.$extra")
    }
    val change =
      volumeChangeFrom(saidLevel)
        ?: return ToolResult.Failed("'$saidLevel' is not a level. Use a percentage, up, down, mute or max.")

    // The ringtone and notification volumes are tied to the ringer: setting them to zero is
    // silent mode, and raising them from silent leaves it, both of which Android guards.
    val ringerLinked = stream == SoundStream.RING || stream == SoundStream.NOTIFICATION
    if (ringerLinked && audio.ringerMode == AudioManager.RINGER_MODE_SILENT && !policyAccess(context)) {
      return askForPolicyAccess(context, guard, "raise the ${stream.label} while the phone is on silent")
    }

    val wanted = volumeIndexFor(change, now, min, max)
    return try {
      audio.setStreamVolume(type, wanted, AudioManager.FLAG_SHOW_UI)
      val after = audio.getStreamVolume(type)
      if (after != wanted && wanted != now) {
        ToolResult.Failed(
          "The phone kept the ${stream.label} volume at ${percentOf(after, min, max)}%. Do Not Disturb " +
            "or a volume limit may be holding it."
        )
      } else {
        ToolResult.Ok("Done - the ${stream.label} volume is at ${percentOf(after, min, max)}%.")
      }
    } catch (e: SecurityException) {
      Log.w(TAG, "Volume refused", e)
      if (!policyAccess(context)) askForPolicyAccess(context, guard, "change the ${stream.label} volume right now")
      else ToolResult.Failed("The phone would not allow a ${stream.label} volume change just now.")
    }
  }

  private companion object {
    const val TAG = "LinVolume"
  }
}

/**
 * Silent, vibrate or sound. Vibrate and sound are Lin's to set; silent (and leaving it) is
 * guarded by Do Not Disturb access on current Android, which this asks for once - or, with
 * Shizuku lent, sets through the shell.
 */
class SoundModeTool(
  private val context: Context,
  private val guard: LaunchGuard,
  private val privileged: Privileged,
) : AgentTool {

  override val name = "sound_mode"

  override val description = "Put the phone on silent, vibrate or sound (ring) mode, or say which it is on."

  override val parameters =
    listOf(
      ToolParameter(
        name = "mode",
        type = ParamType.STRING,
        description = "silent, vibrate or sound. Leave it out to be told the current mode.",
        required = false,
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
    "Use sound_mode for silent, vibrate or sound/ring mode. Read the result: if it says not " +
      "changed yet, tell the user what to allow rather than saying it is done."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val audio = audioOf(context) ?: return ToolResult.Failed("The ringer cannot be reached on this phone.")
    val said = (arguments["mode"] as? String)?.trim().orEmpty()
    if (said.isEmpty()) return ToolResult.Ok("The phone is in ${ringerOf(audio).label} mode.")
    val wanted =
      ringerModeNamed(said) ?: return ToolResult.Failed("'$said' is not a mode. Use silent, vibrate or sound.")
    if (ringerOf(audio) == wanted) return ToolResult.Ok("Done - the phone is already in ${wanted.label} mode.")

    val value =
      when (wanted) {
        RingerMode.SOUND -> AudioManager.RINGER_MODE_NORMAL
        RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
      }
    val direct =
      runCatching {
          audio.ringerMode = value
          true
        }
        .onFailure { Log.w(TAG, "Ringer refused", it) }
        .getOrDefault(false)
    if (direct && settled(audio, wanted)) return ToolResult.Ok("Done - the phone is in ${wanted.label} mode.")

    if (privileged.isReady()) {
      val word = when (wanted) { RingerMode.SOUND -> "NORMAL"; RingerMode.VIBRATE -> "VIBRATE"; RingerMode.SILENT -> "SILENT" }
      privileged.runFirstThatWorks(listOf(ShellCommand(listOf("cmd", "audio", "set-ringer-mode", word))))
      if (settled(audio, wanted)) return ToolResult.Ok("Done - the phone is in ${wanted.label} mode. Lin set it directly.")
    }

    if (!policyAccess(context)) return askForPolicyAccess(context, guard, "switch to ${wanted.label} mode")
    return ToolResult.Failed(
      "The phone stayed in ${ringerOf(audio).label} mode. Do Not Disturb or a Samsung mode may be holding it."
    )
  }

  /** The ringer can take a moment to report the change; read it back rather than assume. */
  private suspend fun settled(audio: AudioManager, wanted: RingerMode): Boolean {
    repeat(5) {
      if (ringerOf(audio) == wanted) return true
      delay(60)
    }
    return false
  }

  private companion object {
    const val TAG = "LinSoundMode"
  }
}
