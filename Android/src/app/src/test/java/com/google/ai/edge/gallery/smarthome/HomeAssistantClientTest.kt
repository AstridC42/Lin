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

package com.google.ai.edge.gallery.smarthome

import com.google.ai.edge.gallery.smarthome.HomeAssistantClient.Companion.normalizeBaseUrl
import com.google.ai.edge.gallery.smarthome.HomeAssistantClient.Companion.serviceFor
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAssistantClientTest {

  @Test
  fun addsAMissingSchemeAndTrimsTrailingSlashes() {
    assertEquals("http://homeassistant.local:8123", normalizeBaseUrl("homeassistant.local:8123"))
    assertEquals("http://homeassistant.local:8123", normalizeBaseUrl("http://homeassistant.local:8123/"))
    assertEquals("https://ha.example.org", normalizeBaseUrl("  https://ha.example.org/  "))
  }

  @Test
  fun leavesAnEmptyAddressAlone() {
    assertEquals("", normalizeBaseUrl(""))
    assertEquals("", normalizeBaseUrl("   "))
  }

  /**
   * Home Assistant names its on/off services per domain, and several have no `turn_on` at all.
   *
   * Calling `turn_on` on a lock fails with "service not found", which tells the user nothing about
   * what they should have said - and reads as the lock not being controllable rather than as the
   * wrong verb being used.
   */
  @Test
  fun usesTheServiceEachDomainActuallyHas() {
    assertEquals("turn_on", serviceFor("light", on = true))
    assertEquals("turn_off", serviceFor("light", on = false))
    assertEquals("turn_on", serviceFor("switch", on = true))

    assertEquals("open_cover", serviceFor("cover", on = true))
    assertEquals("close_cover", serviceFor("cover", on = false))

    assertEquals("unlock", serviceFor("lock", on = true))
    assertEquals("lock", serviceFor("lock", on = false))

    assertEquals("start", serviceFor("vacuum", on = true))
    assertEquals("return_to_base", serviceFor("vacuum", on = false))

    assertEquals("press", serviceFor("button", on = true))
  }

  /** A scene has no "off": turning one on is the whole of what it does. */
  @Test
  fun turnsASceneOnEitherWay() {
    assertEquals("turn_on", serviceFor("scene", on = true))
    assertEquals("turn_on", serviceFor("scene", on = false))
  }

  /** An unknown domain falls back to the common pair rather than failing to name a service. */
  @Test
  fun fallsBackToTurnOnAndOff() {
    assertEquals("turn_on", serviceFor("input_boolean", on = true))
    assertEquals("turn_off", serviceFor("humidifier", on = false))
    assertEquals("turn_on", serviceFor("something_new", on = true))
  }
}
