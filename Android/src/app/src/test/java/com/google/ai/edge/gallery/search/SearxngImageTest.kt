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

package com.google.ai.edge.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The URL handling behind showing a picture in the chat.
 *
 * SearXNG returns image addresses in three shapes and they cannot be used interchangeably: an
 * absolute URL, a protocol-relative one, and a path relative to the instance - the last being what
 * its own image proxy returns, which is the common case on a hardened instance. Treating a relative
 * one as absolute produces a request to nowhere, and that would surface to the user as "no pictures
 * found" for a query that in fact returned plenty.
 */
class SearxngImageTest {

  /** Mirrors the resolution the client does for `img_src`. */
  private fun absolute(candidate: String, baseUrl: String): String =
    when {
      candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
      candidate.startsWith("//") -> "https:$candidate"
      candidate.startsWith("/") -> "$baseUrl$candidate"
      else -> candidate
    }

  private val base = "https://searx.example.org"

  @Test
  fun leavesAnAbsoluteAddressAlone() {
    assertEquals(
      "https://upload.example.org/fuji.jpg",
      absolute("https://upload.example.org/fuji.jpg", base),
    )
    assertEquals(
      "http://upload.example.org/fuji.jpg",
      absolute("http://upload.example.org/fuji.jpg", base),
    )
  }

  @Test
  fun givesAProtocolRelativeAddressAScheme() {
    assertEquals("https://cdn.example.org/fuji.jpg", absolute("//cdn.example.org/fuji.jpg", base))
  }

  /** The instance's own image proxy - the shape that breaks if treated as absolute. */
  @Test
  fun resolvesTheInstancesOwnProxyAgainstTheInstance() {
    assertEquals(
      "https://searx.example.org/image_proxy?url=abc",
      absolute("/image_proxy?url=abc", base),
    )
  }
}
