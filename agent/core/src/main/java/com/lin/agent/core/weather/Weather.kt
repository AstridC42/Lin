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

package com.lin.agent.core.weather

import kotlin.math.round
import org.json.JSONObject

/** What it is doing outside right now. */
data class Now(val temperature: Double, val feelsLike: Double?, val code: Int)

/** One day ahead: the range, and how likely rain is. */
data class Day(val date: String, val high: Double, val low: Double, val code: Int, val rainChance: Int?)

/** A forecast, as much of it as arrived. */
data class Forecast(val now: Now?, val days: List<Day>, val unit: String = "°C")

/**
 * Weather, from Open-Meteo.
 *
 * Chosen because it needs no account and no key: every other free weather API wants a sign-up,
 * and an assistant that cannot answer "what is it doing outside" until its owner registers with
 * somebody is not finished. It is still a third party, which is a real departure for this app -
 * see the note where the coordinates are rounded.
 */
fun forecastFrom(json: String): Forecast? {
  val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
  if (root.optBoolean("error", false)) return null

  val unit =
    root.optJSONObject("current_units")?.optString("temperature_2m").orEmpty().ifBlank {
      root.optJSONObject("daily_units")?.optString("temperature_2m_max").orEmpty()
    }

  val current = root.optJSONObject("current")
  val now =
    current?.let {
      if (!it.has("temperature_2m")) null
      else
        Now(
          temperature = it.getDouble("temperature_2m"),
          feelsLike = if (it.has("apparent_temperature")) it.getDouble("apparent_temperature") else null,
          code = it.optInt("weather_code", -1),
        )
    }

  val daily = root.optJSONObject("daily")
  val dates = daily?.optJSONArray("time")
  val highs = daily?.optJSONArray("temperature_2m_max")
  val lows = daily?.optJSONArray("temperature_2m_min")
  val codes = daily?.optJSONArray("weather_code")
  val rain = daily?.optJSONArray("precipitation_probability_max")

  val days = mutableListOf<Day>()
  if (dates != null && highs != null && lows != null) {
    // Only as far as every array goes together. A response trimmed mid-flight gives arrays of
    // different lengths, and reading past the shortest is how a forecast gains a day with
    // somebody else's temperature in it.
    val count = minOf(dates.length(), highs.length(), lows.length())
    for (i in 0 until count) {
      days.add(
        Day(
          date = dates.optString(i),
          high = highs.optDouble(i),
          low = lows.optDouble(i),
          code = codes?.optInt(i, -1) ?: -1,
          rainChance = rain?.let { if (i < it.length()) it.optInt(i, -1).takeIf { p -> p >= 0 } else null },
        )
      )
    }
  }

  if (now == null && days.isEmpty()) return null
  return Forecast(now = now, days = days, unit = unit.ifBlank { "°C" })
}

/**
 * The WMO code in words.
 *
 * The codes come back as bare numbers, and a model handed "weather_code 61" will say something
 * confident and wrong about it. Unknown stays unknown rather than becoming "clear", because a
 * made-up clear sky is the one answer somebody might walk out in.
 */
fun inWords(code: Int): String =
  when (code) {
    0 -> "clear"
    1 -> "mostly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "foggy"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    71 -> "light snow"
    73 -> "snow"
    75 -> "heavy snow"
    77 -> "snow grains"
    80 -> "light showers"
    81 -> "showers"
    82 -> "heavy showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorms"
    96, 99 -> "thunderstorms with hail"
    else -> ""
  }

/**
 * The forecast as a sentence for the model to use.
 *
 * Whole degrees. A tenth of a degree is noise a forecast cannot support, and reading "it is
 * 14.3 degrees" out loud is worse than saying fourteen.
 */
fun describe(forecast: Forecast, place: String, daysAhead: Int = 0): String {
  val where = if (place.isBlank()) "" else " in $place"
  if (daysAhead == 0) {
    val now = forecast.now
    val today = forecast.days.firstOrNull()
    val parts = mutableListOf<String>()
    if (now != null) {
      val words = inWords(now.code)
      parts.add("Right now$where it is ${whole(now.temperature)}${forecast.unit}" + if (words.isEmpty()) "." else ", $words.")
      if (now.feelsLike != null && kotlin.math.abs(now.feelsLike - now.temperature) >= 2) {
        parts.add("It feels like ${whole(now.feelsLike)}${forecast.unit}.")
      }
    }
    if (today != null) {
      parts.add(
        "Today: ${whole(today.high)} high, ${whole(today.low)} low" +
          (today.rainChance?.let { ", $it% chance of rain" } ?: "") +
          "."
      )
    }
    return parts.joinToString(" ").ifBlank { "No weather came back." }
  }

  val day = forecast.days.getOrNull(daysAhead) ?: return "The forecast does not reach that far."
  val words = inWords(day.code)
  return buildString {
    append(if (daysAhead == 1) "Tomorrow$where" else "${day.date}$where")
    append(": ${whole(day.high)} high, ${whole(day.low)} low")
    if (words.isNotEmpty()) append(", $words")
    day.rainChance?.let { append(", $it% chance of rain") }
    append(".")
  }
}

/** Which day somebody meant, as days from today. Null when they did not say one Lin knows. */
fun daysAhead(said: String): Int? {
  val text = said.trim().lowercase()
  return when {
    text.isEmpty() -> 0
    // Before "tomorrow", which it contains: "the day after tomorrow" is not tomorrow, and
    // answering the wrong day is a wrong answer that looks like a right one.
    text.contains("day after") -> 2
    text.contains("tomorrow") -> 1
    text.contains("today") || text.contains("now") || text.contains("outside") -> 0
    text.contains("week") -> 6
    else -> null
  }
}

/**
 * Coordinates rounded before they leave the phone.
 *
 * Two decimal places is about a kilometre, which is the same weather and a different street.
 * This is the one place in the app where something about the user goes to a third party, and
 * sending the exact spot when a rounded one gives the identical answer would be careless with
 * it for nothing.
 */
fun rounded(coordinate: Double): Double = round(coordinate * 100) / 100

private fun whole(value: Double): String = round(value).toInt().toString()
