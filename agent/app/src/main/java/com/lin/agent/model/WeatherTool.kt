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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.lin.agent.core.weather.daysAhead
import com.lin.agent.core.weather.describe
import com.lin.agent.core.weather.forecastFrom
import com.lin.agent.core.weather.rounded
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What it is doing outside.
 *
 * Open-Meteo, because it needs no account and no key. Every other free weather service wants a
 * sign-up, and an assistant that cannot answer "do I need a coat" until its owner has
 * registered with somebody is not finished.
 *
 * It is also the first thing in this app that sends anything about the user to a third party,
 * and that is worth being exact about rather than quiet about. What goes is a pair of
 * coordinates rounded to about a kilometre - the same weather, a different street - and
 * nothing else: no identifier, no question, no conversation. The setting says so in those
 * words, and the tool is only ever called when somebody asks about the weather.
 *
 * Location comes from the platform's last known fix rather than from a fresh one. Asking for a
 * new fix wakes the GPS and takes seconds; the last one is good enough for a forecast, and the
 * failure mode of having none is reported rather than papered over.
 */
class WeatherTool(private val context: Context) : AgentTool {

  override val name = "weather"

  override val description = "What the weather is doing, here, today or in the next few days."

  override val parameters =
    listOf(
      ToolParameter(
        name = "when",
        type = ParamType.STRING,
        description = "now, today, tomorrow, or the day after. Leave out for now.",
        required = false,
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["when"] as? String).orEmpty()
    val ahead =
      daysAhead(asked)
        ?: return ToolResult.Failed(
          "Lin can only do today, tomorrow and the day after. Say which of those, or say that " +
            "it cannot look that far."
        )

    if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
      return ToolResult.Failed(
        "Lin has not been given location, so it does not know where to look up the weather. " +
          "Tell the user it is in settings, under Weather."
      )
    }

    val fix =
      lastKnown()
        ?: return ToolResult.Failed(
          "This phone has no recent position to look the weather up for. Tell the user to open " +
            "a maps app once, or turn location on, rather than guessing a place."
        )

    val body =
      withContext(Dispatchers.IO) { fetch(rounded(fix.latitude), rounded(fix.longitude)) }
        ?: return ToolResult.Failed("The weather service could not be reached.")

    val forecast =
      forecastFrom(body) ?: return ToolResult.Failed("The weather service sent something unreadable.")

    return ToolResult.Ok(describe(forecast, place = "", daysAhead = ahead))
  }

  /**
   * The newest fix any provider still holds.
   *
   * Both providers are asked because they fail in opposite places: the network one is empty
   * with no signal, and GPS is empty indoors.
   */
  private fun lastKnown(): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return runCatching {
        listOfNotNull(
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
          )
          .maxByOrNull { it.time }
      }
      .getOrNull()
  }

  private fun fetch(latitude: Double, longitude: Double): String? =
    runCatching {
        val timezone = URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8")
        val url =
          "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
            "&timezone=$timezone&forecast_days=4"
        val connection =
          (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
          }
        connection.use { it.inputStream.bufferedReader().readText() }
      }
      .onFailure { Log.w(TAG, "Weather lookup failed", it) }
      .getOrNull()

  private fun granted(permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try {
      block(this)
    } finally {
      disconnect()
    }

  private companion object {
    const val TAG = "LinWeather"
    const val TIMEOUT_MS = 8_000
  }
}
