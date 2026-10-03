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

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.gallery.smarthome.HomeAssistantClient
import com.google.ai.edge.gallery.smarthome.HomeOutcome
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Smart home control, through Home Assistant.
 *
 * Only offered when an instance is configured, so a model on a phone without one is never told
 * about a tool that could only ever fail.
 *
 * Google Home is deliberately absent, and it is worth saying why rather than leaving it looking
 * like an oversight. Google exposes no general API for a third-party Android app to control
 * devices in someone's Home structure: the Home APIs require registration in the Google Home
 * Developer Console, an OAuth consent screen and per-project approval, none of which a sideloaded
 * app can satisfy. Home Assistant's own Google Cast and Nest integrations are the practical route
 * to those devices, and they come in through this same tool.
 */
class SmartHomeTool(private val client: HomeAssistantClient) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "List controllable smart home devices. Call first to get exact device ids."
  )
  fun listSmartHomeDevices(
    @ToolParam(description = "Optional name to filter by, for example 'kitchen'. Empty for all.")
    filter: String = ""
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      when (val outcome = client.listEntities(filter)) {
        is HomeOutcome.NotConfigured -> notConfigured()
        is HomeOutcome.Failed -> failure(outcome.message)
        // listEntities never reports a service call; treated as a failure rather than ignored so
        // an unexpected shape is reported instead of coming back as an empty device list.
        is HomeOutcome.Called -> failure("Unexpected response while listing devices.")
        is HomeOutcome.Entities -> {
          if (outcome.entities.isEmpty()) {
            return@runBlocking mapOf(
              "status" to "succeeded",
              "devices" to listOf<Map<String, Any>>(),
              "hint" to
                if (filter.isBlank()) "Home Assistant reported no controllable devices."
                else "No device matched \"$filter\". Ask the user what it is called.",
            )
          }
          sendProgress("Found ${outcome.entities.size} device(s)", inProgress = false)
          mapOf(
            "status" to "succeeded",
            "devices" to
              outcome.entities.take(MAX_DEVICES).map {
                mapOf(
                  "id" to it.entityId,
                  "name" to it.name,
                  "state" to it.state,
                  "kind" to it.domain,
                )
              },
            "hint" to "Use the id with setSmartHomeDevice.",
          )
        }
      }
    }
  }

  @Tool(
    description =
      "Turn a smart home device on or off, or open/close it."
  )
  fun setSmartHomeDevice(
    @ToolParam(description = "The device id from listSmartHomeDevices, for example light.kitchen.")
    deviceId: String,
    @ToolParam(description = "true to turn on, unlock or open; false to turn off, lock or close.")
    on: Boolean,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (deviceId.isBlank()) {
        return@runBlocking failure("No device id was given. Call listSmartHomeDevices first.")
      }
      val domain = deviceId.substringBefore('.', "")
      val service = HomeAssistantClient.serviceFor(domain, on)
      when (val outcome = client.callService(deviceId, service)) {
        is HomeOutcome.NotConfigured -> notConfigured()
        is HomeOutcome.Failed -> failure(outcome.message)
        else -> {
          sendProgress(if (on) "Turned on" else "Turned off", inProgress = false)
          mapOf("status" to "succeeded", "device" to deviceId, "service" to service)
        }
      }
    }
  }

  @Tool(
    description =
      "Set a light brightness, fan speed, blind position or target temperature."
  )
  fun setSmartHomeLevel(
    @ToolParam(description = "The device id, for example light.kitchen.") deviceId: String,
    @ToolParam(
      description =
        "For a light, brightness from 0 to 100. For a thermostat, the target temperature in the " +
          "user's own units."
    )
    level: Int,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (deviceId.isBlank()) {
        return@runBlocking failure("No device id was given. Call listSmartHomeDevices first.")
      }
      val domain = deviceId.substringBefore('.', "")
      // The two kinds of level are different services with differently named parameters, and
      // sending a brightness to a thermostat would be accepted and ignored rather than refused.
      val (service, data) =
        when (domain) {
          "light" -> "turn_on" to mapOf<String, Any>("brightness_pct" to level.coerceIn(0, 100))
          "climate" -> "set_temperature" to mapOf<String, Any>("temperature" to level)
          "fan" -> "set_percentage" to mapOf<String, Any>("percentage" to level.coerceIn(0, 100))
          "cover" -> "set_cover_position" to mapOf<String, Any>("position" to level.coerceIn(0, 100))
          else ->
            return@runBlocking failure(
              "A level cannot be set on \"$deviceId\". Use setSmartHomeDevice to turn it on or off."
            )
        }
      when (val outcome = client.callService(deviceId, service, data)) {
        is HomeOutcome.NotConfigured -> notConfigured()
        is HomeOutcome.Failed -> failure(outcome.message)
        else -> {
          sendProgress("Set to $level", inProgress = false)
          mapOf("status" to "succeeded", "device" to deviceId, "level" to level)
        }
      }
    }
  }

  private fun notConfigured(): Map<String, Any> =
    failure(
      "No Home Assistant instance is set up, so smart home devices cannot be reached. Tell the " +
        "user they can add one under the smart home settings in the Guide panel. Do not claim " +
        "the device does not exist."
    )

  private suspend fun sendProgress(label: String, inProgress: Boolean) {
    executionContext
      ?.actionChannel
      ?.send(SkillProgressToolAction(label = label, inProgress = inProgress))
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  companion object {
    /** Enough for a large house without flooding the model's context. */
    private const val MAX_DEVICES = 25
  }
}
