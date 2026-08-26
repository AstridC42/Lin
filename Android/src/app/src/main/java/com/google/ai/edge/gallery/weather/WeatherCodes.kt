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

import java.util.Locale

/**
 * Turning a forecast's numbers into the words a person would use.
 *
 * The World Meteorological Organization's present-weather codes are what every free forecast API
 * returns, and they are integers: 61 is not something to read out. Handing the raw number to the
 * model instead is worse than it looks - a small model will guess, confidently, and "code 61" comes
 * back as anything from clear skies to a hurricane.
 *
 * Free of Android so the mapping can be tested, which is the point: a wrong entry here is a
 * plausible-sounding forecast that happens to be about different weather.
 */
object WeatherCodes {

  /**
   * What [code] describes, in a few words.
   *
   * Unknown codes say so rather than guessing. The list has grown before and will again, and an
   * honest gap is recoverable in a way that a wrong description is not.
   */
  fun describe(code: Int): String =
    when (code) {
      0 -> "clear"
      1 -> "mainly clear"
      2 -> "partly cloudy"
      3 -> "overcast"
      45, 48 -> "foggy"
      51 -> "light drizzle"
      53 -> "drizzle"
      55 -> "heavy drizzle"
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
      82 -> "violent showers"
      85 -> "light snow showers"
      86 -> "snow showers"
      95 -> "a thunderstorm"
      96, 99 -> "a thunderstorm with hail"
      else -> "unsettled"
    }

  /**
   * Whether this locale expects Fahrenheit and miles per hour.
   *
   * Answering an American "what's it like out" in Celsius is not a small error - it is an answer
   * that has to be converted before it means anything, which is most of what asking was for. The
   * three countries that use Fahrenheit day to day are the whole list.
   */
  fun usesImperial(locale: Locale): Boolean =
    locale.country.uppercase(Locale.US) in setOf("US", "LR", "MM")

  /** The unit names Open-Meteo expects, matching [usesImperial]. */
  fun temperatureUnit(imperial: Boolean): String = if (imperial) "fahrenheit" else "celsius"

  fun windUnit(imperial: Boolean): String = if (imperial) "mph" else "kmh"

  /** How the units read in an answer. */
  fun temperatureLabel(imperial: Boolean): String = if (imperial) "°F" else "°C"

  fun windLabel(imperial: Boolean): String = if (imperial) "mph" else "km/h"
}
