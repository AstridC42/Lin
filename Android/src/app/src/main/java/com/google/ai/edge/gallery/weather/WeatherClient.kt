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

package com.google.ai.edge.gallery.weather

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "AGWeatherClient"

/** Where a forecast is for. */
data class WeatherPlace(val name: String, val latitude: Double, val longitude: Double)

/** One day of the forecast. */
data class WeatherDay(
  val date: String,
  val description: String,
  val high: Double,
  val low: Double,
  val rainChance: Int,
)

/** A forecast, as far as it was asked for. */
data class WeatherReport(
  val place: String,
  val description: String,
  val temperature: Double,
  val feelsLike: Double,
  val windSpeed: Double,
  val temperatureLabel: String,
  val windLabel: String,
  val days: List<WeatherDay>,
)

sealed interface WeatherOutcome {
  data class Success(val report: WeatherReport) : WeatherOutcome

  data class Failure(val message: String) : WeatherOutcome
}

/**
 * Forecasts, from Open-Meteo.
 *
 * "What's the weather" is the question an assistant gets asked more than any other, and until now
 * this one could only answer it where a SearXNG instance had been set up - which is a self-hosted
 * service most people will never run. Open-Meteo needs no key, no account and no registration, so
 * the weather works on a phone with nothing configured, which is the state every phone is in on the
 * day the app is installed.
 *
 * Two requests, not one: a place name has to be turned into coordinates first, and there is no
 * endpoint that does both. When the caller already has coordinates - from the phone's own last
 * known position - the first request is skipped.
 */
class WeatherClient(private val locale: () -> Locale = { Locale.getDefault() }) {

  /** Looks up where [place] is, or reports that it could not be found. */
  suspend fun locate(place: String): WeatherPlace? =
    withContext(Dispatchers.IO) {
      val url =
        "https://geocoding-api.open-meteo.com/v1/search?name=${encode(place)}&count=1&format=json"
      try {
        val json = JSONObject(httpGet(url))
        val results = json.optJSONArray("results") ?: return@withContext null
        if (results.length() == 0) return@withContext null
        val first = results.getJSONObject(0)
        // The country disambiguates the dozens of Springfields, and is what a person would say
        // back to confirm the assistant understood which one.
        val label =
          listOfNotNull(
              first.optString("name").takeIf { it.isNotBlank() },
              first.optString("country").takeIf { it.isNotBlank() },
            )
            .joinToString(", ")
        WeatherPlace(
          name = label.ifBlank { place },
          latitude = first.getDouble("latitude"),
          longitude = first.getDouble("longitude"),
        )
      } catch (e: Exception) {
        Log.w(TAG, "Could not look up \"$place\"", e)
        null
      }
    }

  /**
   * The forecast at [place], for today plus [days] - 1 more.
   *
   * @param days how many days to return, including today. Clamped to what the service offers.
   */
  suspend fun forecast(place: WeatherPlace, days: Int = 1): WeatherOutcome =
    withContext(Dispatchers.IO) {
      val imperial = WeatherCodes.usesImperial(locale())
      val wanted = days.coerceIn(1, MAX_DAYS)
      val url = buildString {
        append("https://api.open-meteo.com/v1/forecast")
        append("?latitude=${place.latitude}&longitude=${place.longitude}")
        append("&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
        append("&daily=weather_code,temperature_2m_max,temperature_2m_min")
        append(",precipitation_probability_max")
        append("&forecast_days=$wanted&timezone=auto")
        append("&temperature_unit=${WeatherCodes.temperatureUnit(imperial)}")
        append("&wind_speed_unit=${WeatherCodes.windUnit(imperial)}")
      }

      try {
        val json = JSONObject(httpGet(url))
        val current =
          json.optJSONObject("current")
            ?: return@withContext WeatherOutcome.Failure(
              "The forecast service returned nothing for ${place.name}."
            )

        val daily = json.optJSONObject("daily")
        val dates = daily?.optJSONArray("time")
        val codes = daily?.optJSONArray("weather_code")
        val highs = daily?.optJSONArray("temperature_2m_max")
        val lows = daily?.optJSONArray("temperature_2m_min")
        val rain = daily?.optJSONArray("precipitation_probability_max")

        val forecastDays =
          (0 until (dates?.length() ?: 0)).mapNotNull { index ->
            // Any day missing a field is left out rather than reported with a zero in its place:
            // "a high of 0" is a forecast someone might dress for.
            val date = dates?.optString(index) ?: return@mapNotNull null
            val high = highs?.optDouble(index) ?: return@mapNotNull null
            val low = lows?.optDouble(index) ?: return@mapNotNull null
            if (high.isNaN() || low.isNaN()) return@mapNotNull null
            WeatherDay(
              date = date,
              description = WeatherCodes.describe(codes?.optInt(index, -1) ?: -1),
              high = high,
              low = low,
              rainChance = rain?.optInt(index, 0) ?: 0,
            )
          }

        WeatherOutcome.Success(
          WeatherReport(
            place = place.name,
            description = WeatherCodes.describe(current.optInt("weather_code", -1)),
            temperature = current.optDouble("temperature_2m", Double.NaN),
            feelsLike = current.optDouble("apparent_temperature", Double.NaN),
            windSpeed = current.optDouble("wind_speed_10m", Double.NaN),
            temperatureLabel = WeatherCodes.temperatureLabel(imperial),
            windLabel = WeatherCodes.windLabel(imperial),
            days = forecastDays,
          )
        )
      } catch (e: IOException) {
        WeatherOutcome.Failure(
          "The forecast could not be fetched. Check the phone's internet connection."
        )
      } catch (e: Exception) {
        Log.w(TAG, "Forecast request failed", e)
        WeatherOutcome.Failure("The forecast service could not be reached.")
      }
    }

  private fun httpGet(url: String): String {
    var connection: HttpURLConnection? = null
    try {
      connection = URL(url).openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = TIMEOUT_MILLIS
      connection.readTimeout = TIMEOUT_MILLIS
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Accept-Encoding", "gzip")
      val status = connection.responseCode
      if (status !in 200..299) throw IOException("HTTP $status")
      val raw = connection.inputStream
      val stream =
        if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
          GZIPInputStream(raw)
        } else {
          raw
        }
      return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
      connection?.disconnect()
    }
  }

  private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

  private companion object {
    const val TIMEOUT_MILLIS = 12_000

    /** Open-Meteo serves sixteen; more than a week ahead is not a forecast anyone should act on. */
    const val MAX_DAYS = 7
  }
}
