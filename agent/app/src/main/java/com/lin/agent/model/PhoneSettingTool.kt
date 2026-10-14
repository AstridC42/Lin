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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.lin.agent.core.phone.Reach
import com.lin.agent.core.phone.Switch
import com.lin.agent.core.phone.reachOf
import com.lin.agent.core.phone.switchNamed
import com.lin.agent.core.phone.wantedOn
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The switches on the phone: the torch, the radios, Do Not Disturb.
 *
 * What makes this worth having as one tool rather than four is that the answer to "can you?"
 * is different for each and changes with the Android version. The torch is Lin's to set. Wi-Fi
 * was taken from apps in Android 10 and Bluetooth in Android 13; NFC never had an API; aeroplane
 * mode never will. For those, the best an app can honestly do is put the system's own panel in
 * front of the user, which is one tap and shows them exactly what is being changed - so the
 * result says "ready to tap" and never "done". See [reachOf] for the table, which is tested,
 * because the SDK still compiles the calls that no longer work and a tool that used them would
 * report success at nothing.
 *
 * Asking is answered rather than acted on. "Is the flashlight on" hands a whole sentence
 * through as the state on a small model, and reading that as an instruction turns the torch on
 * in somebody's pocket - so a question gets the state back and changes nothing.
 */
class PhoneSettingTool(private val context: Context, private val guard: LaunchGuard) : AgentTool {

  override val name = "phone_setting"

  override val description =
    "Turn something on the phone on or off: flashlight, wi-fi, bluetooth, nfc, do not disturb, " +
      "airplane mode. Also answers what one of them is set to."

  override val parameters =
    listOf(
      ToolParameter(
        name = "setting",
        type = ParamType.STRING,
        description = "What to change: flashlight, wi-fi, bluetooth, nfc, do not disturb, " +
          "airplane mode.",
      ),
      ToolParameter(
        name = "state",
        type = ParamType.STRING,
        description = "on or off. Leave it out to be told what it is set to now.",
        required = false,
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val saidSetting = (arguments["setting"] as? String).orEmpty()
    val switch =
      switchNamed(saidSetting)
        ?: return ToolResult.Failed(
          "Lin cannot reach \"$saidSetting\". It can do the flashlight, wi-fi, bluetooth, nfc, " +
            "do not disturb and airplane mode, and nothing else on the phone. Say so plainly."
        )

    val wanted = wantedOn((arguments["state"] as? String).orEmpty())
    if (wanted == null) return state(switch)

    return when (reachOf(switch, hasPolicyAccess = policyAccess())) {
      Reach.DIRECT -> set(switch, wanted)
      Reach.NEEDS_PERMISSION -> askForPolicyAccess()
      Reach.OPENS_IT -> open(switch, wanted)
    }
  }

  /** What it is doing now, for the switches where that can be read without a permission. */
  private suspend fun state(switch: Switch): ToolResult =
    when (switch) {
      Switch.FLASHLIGHT ->
        when (torchIsOn()) {
          true -> ToolResult.Ok("The flashlight is on.")
          false -> ToolResult.Ok("The flashlight is off.")
          null -> ToolResult.Failed("Lin could not tell whether the flashlight is on.")
        }
      Switch.DO_NOT_DISTURB -> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val filter = manager?.currentInterruptionFilter
        when {
          filter == null -> ToolResult.Failed("Lin could not read Do Not Disturb.")
          filter == NotificationManager.INTERRUPTION_FILTER_ALL ->
            ToolResult.Ok("Do Not Disturb is off.")
          else -> ToolResult.Ok("Do Not Disturb is on.")
        }
      }
      // The rest can be read, but saying "wi-fi is on" from a tool that cannot turn it off
      // invites the model to offer. Opening the panel shows the state and the switch at once.
      else ->
        ToolResult.Ok(
          "Lin cannot read that one. Say that the phone's own panel shows it, and offer to " +
            "open it."
        )
    }

  private suspend fun set(switch: Switch, on: Boolean): ToolResult =
    when (switch) {
      Switch.FLASHLIGHT -> torch(on)
      Switch.DO_NOT_DISTURB -> doNotDisturb(on)
      else -> open(switch, on)
    }

  private fun torch(on: Boolean): ToolResult {
    val cameras = context.getSystemService(CameraManager::class.java)
      ?: return ToolResult.Failed("This phone has no camera service, so no flashlight.")
    return runCatching {
        val id =
          cameras.cameraIdList.firstOrNull { camera ->
            cameras.getCameraCharacteristics(camera)
              .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
          } ?: return ToolResult.Failed("This phone has no flashlight.")
        cameras.setTorchMode(id, on)
        ToolResult.Ok(if (on) "Done - the flashlight is on." else "Done - the flashlight is off.")
      }
      .getOrElse {
        Log.w(TAG, "Torch refused", it)
        // The camera in use by another app is the usual reason, and it is worth saying which.
        ToolResult.Failed(
          "The flashlight would not switch: another app may be using the camera."
        )
      }
  }

  private fun doNotDisturb(on: Boolean): ToolResult {
    val manager = context.getSystemService(NotificationManager::class.java)
      ?: return ToolResult.Failed("Do Not Disturb cannot be reached on this phone.")
    return runCatching {
        manager.setInterruptionFilter(
          if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
          else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        ToolResult.Ok(if (on) "Done - Do Not Disturb is on." else "Done - Do Not Disturb is off.")
      }
      .getOrElse { ToolResult.Failed("The phone would not change Do Not Disturb.") }
  }

  private fun askForPolicyAccess(): ToolResult {
    val opened = launch(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "dnd-access")
    return if (opened) {
      ToolResult.Ok(
        "Done - the Do Not Disturb permission screen is open. Lin has not been given that " +
          "access yet, so say it is waiting for them to allow it, not that it is set."
      )
    } else {
      ToolResult.Failed("Lin needs Do Not Disturb access, and could not open the screen for it.")
    }
  }

  /**
   * The system's own panel, which is as far as Android lets an app go for these.
   *
   * The result leads with "Done" because the thing Lin was able to do is done - the previous
   * wording made the model read an honest "not changed yet" as a failure and call the tool
   * again, which is the loop LaunchGuard exists for.
   */
  private fun open(switch: Switch, on: Boolean): ToolResult {
    val screens = screensFor(switch)
    val opened = screens.any { launch(it, "panel-${switch.name}") }
    val word = if (on) "on" else "off"
    return if (opened) {
      ToolResult.Ok(
        "Done - the phone's own ${label(switch)} panel is open, with the switch on it. Android " +
          "has not let an app turn ${label(switch)} $word by itself for several versions, so " +
          "tell the user it is ready for them to tap, and do not say it is $word."
      )
    } else {
      ToolResult.Failed("Lin could not open the ${label(switch)} panel on this phone.")
    }
  }

  /** Best first: the inline panel, then the full settings screen, then all of settings. */
  private fun screensFor(switch: Switch): List<String> =
    when (switch) {
      Switch.WIFI ->
        listOf(
          AndroidSettings.Panel.ACTION_WIFI,
          AndroidSettings.Panel.ACTION_INTERNET_CONNECTIVITY,
          AndroidSettings.ACTION_WIFI_SETTINGS,
        )
      Switch.BLUETOOTH -> listOf(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)
      Switch.NFC -> listOf(AndroidSettings.Panel.ACTION_NFC, AndroidSettings.ACTION_NFC_SETTINGS)
      Switch.AIRPLANE_MODE -> listOf(AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS)
      else -> listOf(AndroidSettings.ACTION_SETTINGS)
    }

  private fun label(switch: Switch) =
    when (switch) {
      Switch.WIFI -> "wi-fi"
      Switch.BLUETOOTH -> "bluetooth"
      Switch.NFC -> "NFC"
      Switch.AIRPLANE_MODE -> "airplane mode"
      Switch.DO_NOT_DISTURB -> "Do Not Disturb"
      Switch.FLASHLIGHT -> "flashlight"
    }

  /**
   * Starts a screen, once.
   *
   * Guarded like the calendar and the alarm are: a tool result that honestly says "not changed
   * yet" reads to a small model as a failure to retry, and three settings panels stacking up
   * is what that looks like from the outside.
   */
  private fun launch(action: String, key: String): Boolean {
    if (!guard.allow(key, System.currentTimeMillis())) return true
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Started and caught rather than resolved first. Package visibility can hide a screen
    // from resolveActivity that startActivity opens perfectly well, and a false "this phone
    // has no wi-fi panel" is worse than an exception nobody sees.
    return runCatching { context.startActivity(intent) }.isSuccess
  }

  private fun policyAccess(): Boolean =
    runCatching {
        context.getSystemService(NotificationManager::class.java)
          ?.isNotificationPolicyAccessGranted == true
      }
      .getOrDefault(false)

  /**
   * Whether the torch is on, asked of the camera service rather than remembered.
   *
   * Remembering would be wrong within a minute: the quick settings tile, the lock screen and
   * any other app can change it, and a cached answer would have Lin saying the light is off
   * while it is shining. The callback fires immediately with the current state on
   * registration, so this registers, takes the first answer and leaves.
   */
  private suspend fun torchIsOn(): Boolean? {
    val cameras = context.getSystemService(CameraManager::class.java) ?: return null
    var callback: CameraManager.TorchCallback? = null
    return try {
      withTimeoutOrNull(TORCH_READ_MS) {
        suspendCancellableCoroutine { waiting ->
          val listening =
            object : CameraManager.TorchCallback() {
              override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (waiting.isActive) waiting.resume(enabled)
              }

              // Another app has the camera, so there is no answer to give - and "off" would
              // be a guess that reads as fact.
              override fun onTorchModeUnavailable(cameraId: String) {
                if (waiting.isActive) waiting.resume(null)
              }
            }
          callback = listening
          runCatching { cameras.registerTorchCallback(context.mainExecutor, listening) }
            .onFailure { if (waiting.isActive) waiting.resume(null) }
        }
      }
    } finally {
      // In a finally rather than in invokeOnCancellation: the ordinary path here is resuming
      // normally, which never cancels, and a torch callback left registered goes on being
      // called for the life of the process.
      callback?.let { runCatching { cameras.unregisterTorchCallback(it) } }
    }
  }

  private companion object {
    const val TAG = "LinPhoneSetting"
    const val TORCH_READ_MS = 1_000L
  }
}
