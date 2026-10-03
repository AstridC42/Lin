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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.weather.WeatherClient
import com.google.ai.edge.gallery.weather.WeatherOutcome
import com.google.ai.edge.gallery.weather.WeatherPlace
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The forecast.
 *
 * More people ask an assistant about the weather than about anything else, and until this existed
 * the only way to answer was a web search against a self-hosted SearXNG instance - so on a phone
 * with nothing configured, which is every phone on the day it is installed, the answer was that it
 * could not be done. Open-Meteo needs no key and no account, so this works out of the box.
 *
 * Location is used when there is one and asked for when there is not. A coarse fix is enough for a
 * forecast - the weather is the same across a town - so nothing here ever asks for precise
 * location, and nothing here ever starts a location update: only the fix the phone already has.
 */
class WeatherTool(
  private val context: Context,
  private val client: WeatherClient = WeatherClient(),
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Get the weather now and the forecast for the next few days. Use for \"what's the weather\", " +
        "\"will it rain\", \"do I need a coat\"."
  )
  fun getWeather(
    @ToolParam(
      description = "Town or city to ask about. Leave empty for wherever the phone is."
    )
    place: String = "",
    @ToolParam(description = "How many days, counting today. 1 for right now.") days: Int = 1,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      sendProgress("Checking the weather", inProgress = true)

      val located =
        if (place.isNotBlank()) {
          client.locate(place.trim())
            ?: return@runBlocking failure(
              "There is no town called \"$place\" that the forecast service recognises. Ask the " +
                "user to spell it or name a larger place nearby."
            )
        } else {
          hereOrNull()
            ?: return@runBlocking failure(
              "The phone's location is not available, so \"here\" cannot be resolved. Ask the " +
                "user which town they want the weather for. They can also allow location under " +
                "Weather in the Guide panel."
            )
        }

      when (val outcome = client.forecast(located, days)) {
        is WeatherOutcome.Failure -> {
          sendProgress("Could not get the weather", inProgress = false)
          failure(outcome.message)
        }
        is WeatherOutcome.Success -> {
          val report = outcome.report
          sendProgress(
            "Weather for ${report.place}",
            inProgress = false,
            itemTitle = "Weather",
            itemDescription = "- ${report.place}: ${report.description}",
          )
          buildMap<String, Any> {
            put("status", "succeeded")
            put("place", report.place)
            put("now", report.description)
            if (!report.temperature.isNaN()) {
              put("temperature", "${report.temperature.roundedString()}${report.temperatureLabel}")
            }
            if (!report.feelsLike.isNaN()) {
              put("feels_like", "${report.feelsLike.roundedString()}${report.temperatureLabel}")
            }
            if (!report.windSpeed.isNaN()) {
              put("wind", "${report.windSpeed.roundedString()} ${report.windLabel}")
            }
            if (report.days.isNotEmpty()) {
              put(
                "forecast",
                report.days.map { day ->
                  mapOf(
                    "date" to day.date,
                    "weather" to day.description,
                    "high" to "${day.high.roundedString()}${report.temperatureLabel}",
                    "low" to "${day.low.roundedString()}${report.temperatureLabel}",
                    "rain_chance" to "${day.rainChance}%",
                  )
                },
              )
            }
            put("hint", "Answer in a sentence or two. Do not read every number back.")
          }
        }
      }
    }
  }

  /**
   * Where the phone last knew it was, or null.
   *
   * Deliberately the last known fix rather than a fresh one: requesting an update means holding a
   * listener open and waiting on a GPS lock that may never come indoors, behind a question someone
   * expects answered in a second.
   */
  @SuppressLint("MissingPermission") // Checked immediately below.
  private fun hereOrNull(): WeatherPlace? {
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return null
    }
    val manager =
      context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val fix =
      try {
        // Network first: it is the one a coarse permission actually gets, and indoors it is the
        // only one there is.
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, "fused")
          .asSequence()
          .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
          }
          .maxByOrNull { it.time }
      } catch (e: Exception) {
        null
      } ?: return null

    return WeatherPlace(
      name = nameOf(fix.latitude, fix.longitude),
      latitude = fix.latitude,
      longitude = fix.longitude,
    )
  }

  /** A town name for a fix, falling back to something sayable rather than to coordinates. */
  private fun nameOf(latitude: Double, longitude: Double): String =
    try {
      @Suppress("DEPRECATION")
      Geocoder(context, Locale.getDefault())
        .getFromLocation(latitude, longitude, 1)
        ?.firstOrNull()
        ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        .orEmpty()
        .ifBlank { HERE }
    } catch (e: Exception) {
      // No geocoder backend on the device, or it is offline. The forecast is still correct; only
      // the label is missing, and coordinates read aloud help nobody.
      HERE
    }

  private suspend fun sendProgress(
    label: String,
    inProgress: Boolean,
    itemTitle: String = "",
    itemDescription: String = "",
  ) {
    executionContext
      ?.actionChannel
      ?.send(
        SkillProgressToolAction(
          label = label,
          inProgress = inProgress,
          addItemTitle = itemTitle,
          addItemDescription = itemDescription,
        )
      )
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  private fun Double.roundedString(): String = Math.round(this).toString()

  private companion object {
    const val HERE = "your area"
  }
}
