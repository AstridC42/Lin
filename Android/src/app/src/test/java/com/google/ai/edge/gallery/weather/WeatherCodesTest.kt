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

package com.google.ai.edge.gallery.weather

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a forecast out loud.
 *
 * Every free forecast service returns the World Meteorological Organization's present-weather
 * codes, which are integers. Handing "61" to a small model and letting it work out what that means
 * produces a confident answer that is sometimes about entirely different weather, so the number is
 * turned into words here, where being wrong is a failing test rather than someone leaving without
 * a coat.
 */
class WeatherCodesTest {

  @Test
  fun namesTheOrdinaryConditions() {
    assertEquals("clear", WeatherCodes.describe(0))
    assertEquals("partly cloudy", WeatherCodes.describe(2))
    assertEquals("overcast", WeatherCodes.describe(3))
  }

  /** Rain, snow and thunder are what the question is usually really about. */
  @Test
  fun distinguishesHowWetItIs() {
    assertEquals("light rain", WeatherCodes.describe(61))
    assertEquals("rain", WeatherCodes.describe(63))
    assertEquals("heavy rain", WeatherCodes.describe(65))
    assertEquals("heavy snow", WeatherCodes.describe(75))
    assertEquals("a thunderstorm", WeatherCodes.describe(95))
  }

  /** Freezing rain is not rain, and dressing for one when it is the other is the point. */
  @Test
  fun keepsFreezingSeparateFromWet() {
    assertEquals("freezing rain", WeatherCodes.describe(66))
    assertEquals("freezing drizzle", WeatherCodes.describe(56))
  }

  /**
   * The code list has grown before. An honest "unsettled" can be recovered from; a wrong guess
   * dressed as a forecast cannot.
   */
  @Test
  fun admitsToACodeItDoesNotKnow() {
    assertEquals("unsettled", WeatherCodes.describe(-1))
    assertEquals("unsettled", WeatherCodes.describe(999))
  }

  /** Answering an American in Celsius is an answer that must be converted before it means anything. */
  @Test
  fun picksTheUnitsTheCountryActuallyUses() {
    assertTrue(WeatherCodes.usesImperial(Locale.US))
    assertTrue(WeatherCodes.usesImperial(Locale("en", "LR")))
    assertFalse(WeatherCodes.usesImperial(Locale.UK))
    assertFalse(WeatherCodes.usesImperial(Locale.FRANCE))
    assertFalse(WeatherCodes.usesImperial(Locale.JAPAN))
  }

  /** A locale that names no country at all - which happens - must not guess Fahrenheit. */
  @Test
  fun copesWithALocaleThatNamesNoCountry() {
    assertFalse(WeatherCodes.usesImperial(Locale("en")))
  }

  /** The service's unit names and the spoken ones are different strings and must not be swapped. */
  @Test
  fun keepsTheApiUnitsAndTheSpokenUnitsApart() {
    assertEquals("fahrenheit", WeatherCodes.temperatureUnit(imperial = true))
    assertEquals("celsius", WeatherCodes.temperatureUnit(imperial = false))
    assertEquals("mph", WeatherCodes.windUnit(imperial = true))
    assertEquals("kmh", WeatherCodes.windUnit(imperial = false))
    assertEquals("°F", WeatherCodes.temperatureLabel(imperial = true))
    assertEquals("°C", WeatherCodes.temperatureLabel(imperial = false))
    assertEquals("km/h", WeatherCodes.windLabel(imperial = false))
  }
}
