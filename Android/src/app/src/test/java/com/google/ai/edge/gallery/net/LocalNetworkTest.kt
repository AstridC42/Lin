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

package com.google.ai.edge.gallery.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line between "the user's own network" and "the internet".
 *
 * This is the whole of what replaces Android's cleartext ban, which had to be lifted for a
 * self-hosted service on a home network to be reachable at all. Both directions of error are
 * real. Too strict and the feature stays broken for exactly the setup it exists to serve. Too
 * loose and a Home Assistant token, a SearXNG `Authorization` header and the user's search
 * queries go out over plain HTTP to a host on the internet, readable by every hop in between -
 * and nothing on screen would say so.
 */
class LocalNetworkTest {

  @Test
  fun acceptsThePrivateIpv4Ranges() {
    for (host in
      listOf(
        "192.168.0.9", // the address in the bug report
        "192.168.1.1",
        "10.0.0.1",
        "10.255.255.254",
        "172.16.0.1",
        "172.31.255.254",
        "127.0.0.1",
        "169.254.1.1",
      )) {
      assertTrue("$host is on a home network", LocalNetwork.isPrivateHost(host))
    }
  }

  /** The range Tailscale and similar overlays address from; to the user it is their own network. */
  @Test
  fun acceptsTheCarrierGradeNatRange() {
    assertTrue(LocalNetwork.isPrivateHost("100.64.0.1"))
    assertTrue(LocalNetwork.isPrivateHost("100.127.255.254"))
  }

  /** 172.15 and 172.32 are public, and the range boundary is the easiest thing here to get wrong. */
  @Test
  fun rejectsAddressesJustOutsideTheRanges() {
    for (host in listOf("172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "11.0.0.1")) {
      assertFalse("$host is on the internet", LocalNetwork.isPrivateHost(host))
    }
  }

  @Test
  fun rejectsOrdinaryPublicAddresses() {
    for (host in listOf("8.8.8.8", "93.184.216.34", "example.com", "searx.be")) {
      assertFalse("$host is on the internet", LocalNetwork.isPrivateHost(host))
    }
  }

  /**
   * A public host whose name merely starts with private-looking digits. `192.168.0.9.example.com`
   * resolves on the internet and must not be waved through by a prefix check.
   */
  @Test
  fun isNotFooledByAnAddressShapedName() {
    assertFalse(LocalNetwork.isPrivateHost("192.168.0.9.example.com"))
    assertFalse(LocalNetwork.isPrivateHost("10.0.0.1.attacker.example"))
    assertFalse(LocalNetwork.isPrivateHost("not-192.168.0.9"))
  }

  @Test
  fun acceptsTheLocalNameSuffixes() {
    for (host in
      listOf("localhost", "homeassistant.local", "nas.lan", "router.home", "a.home.arpa")) {
      assertTrue("$host only resolves locally", LocalNetwork.isPrivateHost(host))
    }
  }

  /** A name with no dot cannot come from public DNS, so it came from the local resolver. */
  @Test
  fun acceptsABareHostname() {
    assertTrue(LocalNetwork.isPrivateHost("searxng"))
    assertTrue(LocalNetwork.isPrivateHost("homeassistant"))
  }

  @Test
  fun acceptsLocalIpv6AndRejectsGlobalIpv6() {
    assertTrue(LocalNetwork.isPrivateHost("::1"))
    assertTrue(LocalNetwork.isPrivateHost("[::1]"))
    assertTrue(LocalNetwork.isPrivateHost("fd00::1"))
    assertTrue(LocalNetwork.isPrivateHost("fe80::1%wlan0"))
    assertFalse(LocalNetwork.isPrivateHost("2001:4860:4860::8888"))
  }

  @Test
  fun isNotConfusedByCaseOrATrailingDot() {
    assertTrue(LocalNetwork.isPrivateHost("HomeAssistant.Local."))
    assertFalse(LocalNetwork.isPrivateHost("EXAMPLE.COM"))
  }

  @Test
  fun letsPlainHttpThroughToTheHomeNetworkOnly() {
    assertNull(LocalNetwork.cleartextRefusal("http://192.168.0.9:8081"))
    assertNull(LocalNetwork.cleartextRefusal("http://homeassistant.local:8123/api"))
    assertNotNull(LocalNetwork.cleartextRefusal("http://searx.be/search"))
  }

  /** TLS is always fine, wherever it points. */
  @Test
  fun neverObjectsToHttps() {
    assertNull(LocalNetwork.cleartextRefusal("https://searx.be/search"))
    assertNull(LocalNetwork.cleartextRefusal("https://192.168.0.9:8081"))
  }

  /** A malformed address is the caller's error to report, with its own wording. */
  @Test
  fun saysNothingAboutAnAddressItCannotParse() {
    assertNull(LocalNetwork.cleartextRefusal("not a url"))
    assertNull(LocalNetwork.cleartextRefusal(""))
  }
}
