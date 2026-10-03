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

package com.google.ai.edge.gallery.net

import java.net.URL

/**
 * Which hosts this app will talk to without TLS.
 *
 * The two services a user brings themselves - a SearXNG instance and Home Assistant - almost
 * always live on the home network behind a plain `http://` address with no certificate, because
 * issuing one for `192.168.0.9` is not something a person does on a Saturday. Android has refused
 * cleartext by default since API 28, so every such request failed before it left the phone, and
 * the settings screen cheerfully accepted the address first.
 *
 * The platform's own mechanism cannot express "the local network": a network security config
 * lists domains, not address ranges, and the address is only known once the user types it. So
 * cleartext is permitted in the manifest and narrowed here instead, in the two clients that make
 * these requests, where the rule can actually be written down: plain HTTP to an address on the
 * user's own network, TLS to anything beyond it.
 *
 * That is a real restriction rather than a gesture. Cleartext to a public host is readable and
 * rewritable by every hop in between, and the thing being carried is a Home Assistant token, a
 * SearXNG `Authorization` header, and the user's search queries.
 */
object LocalNetwork {

  /** Suffixes the standards reserve for names that only resolve on a local network. */
  private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal")

  /**
   * Whether [host] is on the user's own network.
   *
   * Covers the private IPv4 ranges, loopback, link-local, the carrier-grade NAT range that
   * Tailscale and similar overlays hand out, IPv6 loopback, link-local and unique-local addresses,
   * the reserved local name suffixes, and a bare single-label name such as `searxng`, which can
   * only come from a local resolver.
   */
  fun isPrivateHost(host: String): Boolean {
    val name = host.trim().removeSurrounding("[", "]").lowercase().trimEnd('.')
    if (name.isEmpty()) return false
    if (name == "localhost") return true

    ipv4Octets(name)?.let { octets ->
      val a = octets[0]
      val b = octets[1]
      return when {
        a == 10 || a == 127 -> true
        a == 192 && b == 168 -> true
        a == 172 && b in 16..31 -> true
        a == 169 && b == 254 -> true
        // 100.64.0.0/10, which is what Tailscale and other overlay networks address from.
        a == 100 && b in 64..127 -> true
        else -> false
      }
    }

    if (name.contains(':')) {
      // An IPv6 literal, possibly with a zone id. ::1 is loopback; fe80::/10 is link-local;
      // fc00::/7 is the unique-local range a home network uses.
      val address = name.substringBefore('%')
      if (address == "::1") return true
      val first = address.substringBefore(':')
      if (first.length >= 2) {
        val prefix = first.take(2).toIntOrNull(16) ?: return false
        if (prefix in 0xfc..0xfd) return true
        if (prefix == 0xfe && first.length >= 3) {
          val nibble = first[2].digitToIntOrNull(16) ?: return false
          if (nibble in 0x8..0xb) return true
        }
      }
      return false
    }

    if (LOCAL_SUFFIXES.any { name.endsWith(it) }) return true
    // A name with no dot cannot be resolved publicly, so it came from the local network.
    return !name.contains('.')
  }

  /** The four octets of [name] when it is an IPv4 literal, or null when it is a name. */
  private fun ipv4Octets(name: String): IntArray? {
    val parts = name.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (i in 0 until 4) {
      // toIntOrNull accepts a leading sign and arbitrary length; an octet is one to three digits.
      val part = parts[i]
      if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
      val value = part.toIntOrNull() ?: return null
      if (value > 255) return null
      octets[i] = value
    }
    return octets
  }

  /**
   * Why [url] will not be fetched, or null when it will be.
   *
   * The message is written to be read by the user, because that is where it ends up: in the
   * settings dialog under the field, and in what the model is told to pass on when a search fails.
   */
  fun cleartextRefusal(url: String): String? {
    val parsed = try {
      URL(url)
    } catch (e: Exception) {
      return null // Malformed addresses are somebody else's error to report.
    }
    if (!parsed.protocol.equals("http", ignoreCase = true)) return null
    val host = parsed.host.orEmpty()
    if (isPrivateHost(host)) return null
    return "$host is not on your network, so it cannot be reached over plain http://. " +
      "Use https:// for an address on the internet."
  }
}
