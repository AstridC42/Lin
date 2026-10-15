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

package com.lin.agent.core.screen

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ChatLinksTest {

  @Test
  fun `whatsapp takes a number with the text already in it`() {
    assertEquals(
      "https://wa.me/972501234567?text=on%20my%20way",
      chatLink("com.whatsapp", "+972 50 123 4567", "on my way"),
    )
  }

  @Test
  fun `a name is not a whatsapp link`() {
    // wa.me takes digits only. A name would open a page saying the number is invalid, which
    // looks to the user like Lin having sent something somewhere.
    assertNull(chatLink("com.whatsapp", "Dad", "on my way"))
  }

  @Test
  fun `something too short to be a number is not treated as one`() {
    assertNull(chatLink("com.whatsapp", "123", "hello"))
    assertNull(chatLink("com.google.android.apps.messaging", "42", "hello"))
  }

  @Test
  fun `messages uses the number and the body`() {
    assertEquals(
      "smsto:0501234567?body=running%20late",
      chatLink("com.google.android.apps.messaging", "0501234567", "running late"),
    )
  }

  @Test
  fun `telegram takes a handle and not a display name`() {
    assertEquals("https://t.me/benelbit", chatLink("org.telegram.messenger", "@benelbit", "hi"))
    assertEquals("https://t.me/ben_elbit", chatLink("org.telegram.messenger", "ben_elbit", "hi"))
    assertNull(chatLink("org.telegram.messenger", "Ben Elbit", "hi"))
    assertNull(chatLink("org.telegram.messenger", "ben", "hi"))
  }

  @Test
  fun `an app with no link says so rather than guessing one`() {
    assertNull(chatLink("org.thoughtcrime.securesms", "+972501234567", "hi"))
    assertNull(chatLink("com.example.chat", "+972501234567", "hi"))
  }

  @Test
  fun `nobody named is no link`() {
    assertNull(chatLink("com.whatsapp", "", "hi"))
    assertNull(chatLink("com.whatsapp", "   ", "hi"))
  }

  @Test
  fun `a space is never a plus sign`() {
    // URLEncoder writes a space as "+", which arrives in a WhatsApp composer as a literal
    // plus between every word. This is why the encoding is written out by hand.
    val link = chatLink("com.whatsapp", "+972501234567", "see you at six")!!
    assertFalse(link, link.contains("+972"))
    assertTrue(link, link.endsWith("see%20you%20at%20six"))
  }

  @Test
  fun `the characters that would break the link are escaped`() {
    val link = chatLink("com.whatsapp", "0501234567", "a&b?c=d#e")!!
    assertTrue(link, link.endsWith("a%26b%3Fc%3Dd%23e"))
  }

  @Test
  fun `letters outside ascii survive`() {
    // A Kotlin Byte is signed, so every accented letter and every emoji arrives as a negative
    // number - which formats as something that is not a percent escape at all.
    val link = chatLink("com.whatsapp", "0501234567", "café")!!
    assertTrue(link, link.endsWith("caf%C3%A9"))
    assertFalse(link, link.contains("-"))
  }

  @Test
  fun `an empty message is a link to the chat`() {
    assertEquals("https://wa.me/972501234567?text=", chatLink("com.whatsapp", "+972501234567", ""))
  }
}
