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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a forecast, and saying only what came back in it. */
class WeatherTest {

  private val reply =
    """
    {
      "current_units": {"temperature_2m": "°C"},
      "current": {"temperature_2m": 14.3, "apparent_temperature": 11.8, "weather_code": 61},
      "daily": {
        "time": ["2026-10-15", "2026-10-16", "2026-10-17"],
        "temperature_2m_max": [16.2, 18.9, 14.0],
        "temperature_2m_min": [9.4, 11.1, 8.2],
        "weather_code": [61, 2, 95],
        "precipitation_probability_max": [80, 10, 60]
      }
    }
    """
      .trimIndent()

  @Test
  fun `a forecast is read`() {
    val forecast = forecastFrom(reply)!!
    assertEquals(14.3, forecast.now!!.temperature, 0.001)
    assertEquals(61, forecast.now!!.code)
    assertEquals(3, forecast.days.size)
    assertEquals(80, forecast.days[0].rainChance)
  }

  @Test
  fun `an error is not a forecast`() {
    assertNull(forecastFrom("""{"error": true, "reason": "Latitude must be in range"}"""))
    assertNull(forecastFrom("not json at all"))
    assertNull(forecastFrom("{}"))
  }

  @Test
  fun `arrays that disagree in length do not invent a day`() {
    // A response cut short gives arrays of different lengths, and reading past the shortest
    // is how a forecast gains a day with somebody else's temperature in it.
    val short =
      """
      {"daily": {"time": ["2026-10-15","2026-10-16"], "temperature_2m_max": [16.2],
       "temperature_2m_min": [9.4], "weather_code": [61]}}
      """
        .trimIndent()
    assertEquals(1, forecastFrom(short)!!.days.size)
  }

  @Test
  fun `a code nobody knows stays unsaid`() {
    // A made-up "clear" is the one answer somebody might walk out in.
    assertEquals("", inWords(-1))
    assertEquals("", inWords(4242))
    assertEquals("heavy rain", inWords(65))
  }

  @Test
  fun `now is whole degrees and says what it is doing`() {
    val said = describe(forecastFrom(reply)!!, place = "Tel Aviv", daysAhead = 0)
    assertTrue(said, said.contains("14°C"))
    assertTrue(said, said.contains("light rain"))
    assertTrue(said, said.contains("Tel Aviv"))
    // A tenth of a degree is noise a forecast cannot support.
    assertTrue(said, !said.contains("14.3"))
  }

  @Test
  fun `a feels-like within a couple of degrees is not worth saying`() {
    val close =
      """{"current": {"temperature_2m": 14.0, "apparent_temperature": 13.4, "weather_code": 0}}"""
    val said = describe(forecastFrom(close)!!, place = "", daysAhead = 0)
    assertTrue(said, !said.contains("feels like"))
  }

  @Test
  fun `tomorrow is the next day, not today`() {
    val said = describe(forecastFrom(reply)!!, place = "", daysAhead = 1)
    assertTrue(said, said.startsWith("Tomorrow"))
    assertTrue(said, said.contains("19") || said.contains("18"))
  }

  @Test
  fun `a day the forecast does not reach is said rather than guessed`() {
    val said = describe(forecastFrom(reply)!!, place = "", daysAhead = 6)
    assertTrue(said, said.contains("does not reach"))
  }

  @Test
  fun `which day somebody meant`() {
    assertEquals(0, daysAhead(""))
    assertEquals(0, daysAhead("right now"))
    assertEquals(1, daysAhead("tomorrow morning"))
    assertEquals(2, daysAhead("the day after tomorrow"))
    // Not understood is not today: answering today's weather to a question about Friday is
    // a wrong answer dressed as a right one.
    assertNull(daysAhead("on friday"))
  }

  @Test
  fun `coordinates are rounded before they leave the phone`() {
    // About a kilometre: the same weather, a different street.
    assertEquals(32.08, rounded(32.0809123), 0.0001)
    assertEquals(34.78, rounded(34.7805678), 0.0001)
    assertEquals(-0.12, rounded(-0.1234567), 0.0001)
  }
}
