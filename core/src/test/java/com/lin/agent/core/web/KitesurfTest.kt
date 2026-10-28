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

package com.lin.agent.core.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitesurfTest {
  private val account = "0123456789abcdef0123456789abcdef"

  @Test
  fun anAccountIsThirtyTwoHexCharacters() {
    assertTrue(Kitesurf.validAccount(account))
    assertTrue(Kitesurf.validAccount("  $account  "))
    assertFalse(Kitesurf.validAccount("0123"))
    assertFalse(Kitesurf.validAccount(account + "g"))
    assertFalse(Kitesurf.validAccount(""))
  }

  @Test
  fun itIsOnlyConfiguredWithBoth() {
    assertTrue(Kitesurf.configured(account, "x".repeat(40)))
    assertFalse(Kitesurf.configured(account, ""))
    assertFalse(Kitesurf.configured("", "x".repeat(40)))
  }

  @Test
  fun theEndpointNamesTheActionAndTheBrowser() {
    assertEquals(
      "https://api.cloudflare.com/client/v4/accounts/$account/browser-run/markdown?browser=kitesurf",
      Kitesurf.endpoint(account, "markdown"),
    )
    assertEquals("Bearer abc", Kitesurf.headers(" abc ")["Authorization"])
  }

  @Test
  fun bodiesCarryTheAddress() {
    assertEquals("https://example.com", JSONObject(Kitesurf.markdownBody("https://example.com")).getString("url"))
    val shot = JSONObject(Kitesurf.screenshotBody("https://example.com"))
    assertEquals(412, shot.getJSONObject("viewport").getInt("width"))
    assertEquals("https://example.com", shot.getString("url"))
  }

  @Test
  fun readsTheMarkdownEnvelope() {
    assertEquals("# Hi", Kitesurf.markdownFrom("""{"success":true,"result":"# Hi"}"""))
    assertNull(Kitesurf.markdownFrom("""{"success":false,"result":"x"}"""))
    assertNull(Kitesurf.markdownFrom("""{"success":true,"result":"  "}"""))
    assertNull(Kitesurf.markdownFrom("not json"))
    assertEquals("Bad token", Kitesurf.errorFrom("""{"success":false,"errors":[{"code":1,"message":"Bad token"}]}"""))
    assertNull(Kitesurf.errorFrom("""{"success":true}"""))
  }

  @Test
  fun statusesSayWhatToChange() {
    assertTrue(Kitesurf.describeStatus(401).contains("Browser Run - Edit"))
    assertTrue(Kitesurf.describeStatus(429).contains("ten seconds"))
    assertTrue(Kitesurf.describeStatus(500, "boom").contains("boom"))
  }

  @Test
  fun addressesAreFixedOrRefused() {
    assertEquals("https://example.com/a", Kitesurf.pageAddress("example.com/a"))
    assertEquals("http://example.com", Kitesurf.pageAddress("http://example.com"))
    assertNull(Kitesurf.pageAddress("localhost"))
    assertNull(Kitesurf.pageAddress("two words.com"))
  }
}
