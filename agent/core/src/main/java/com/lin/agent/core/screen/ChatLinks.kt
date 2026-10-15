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

/**
 * A link that opens one conversation in one app, with the message already typed.
 *
 * Worth having even though Lin can type: a link that lands on the right chat with the right
 * words in the box means the accessibility service has one thing left to do - press Send - and
 * the step that could go to the wrong person never happens. The three here are the documented,
 * long-stable ones. Everything else returns null, which means Lin opens the app and types,
 * and the person is told which chat it is waiting in.
 *
 * `null` is a normal answer, not a failure.
 */
fun chatLink(packageName: String, to: String, text: String): String? {
  val who = to.trim()
  if (who.isEmpty()) return null
  val body = encode(text)
  return when (packageName) {
    // wa.me is WhatsApp's own documented link and takes a number in full international form
    // with nothing but digits. A name cannot be used, so a name means no link.
    "com.whatsapp",
    "com.whatsapp.w4b" -> digitsOf(who)?.let { "https://wa.me/$it?text=$body" }
    "org.telegram.messenger",
    "org.telegram.messenger.web" -> handleOf(who)?.let { "https://t.me/$it" }
    // The one that is part of Android rather than part of an app: smsto: opens whichever
    // messaging app the user has chosen, at that number, with the body filled in.
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging" -> digitsOf(who)?.let { "smsto:$it?body=$body" }
    else -> null
  }
}

/**
 * [who] as a bare international number, or null if it is a name.
 *
 * A leading `+` is dropped rather than kept: wa.me takes digits only, and a `+` in the path
 * is the difference between opening the chat and opening a page that says the number is
 * invalid. Fewer than seven digits is not a phone number - it is a house number or a year
 * that the model has handed through - and guessing it is one would open a chat with a stranger.
 */
private fun digitsOf(who: String): String? {
  if (who.any { it.isLetter() }) return null
  val digits = who.filter { it.isDigit() }
  return digits.takeIf { it.length >= SHORTEST_NUMBER }
}

/** A Telegram @handle without the @, or null when what was given is not one. */
private fun handleOf(who: String): String? {
  val handle = who.removePrefix("@").trim()
  if (handle.length < SHORTEST_HANDLE) return null
  // Telegram handles are letters, digits and underscores. A space means a display name, which
  // t.me cannot resolve - "Ben Elbit" would open a page about a username that does not exist.
  return handle.takeIf { it.all { c -> c.isLetterOrDigit() || c == '_' } }
}

/**
 * Percent-encoding, by hand, for the characters that matter in a message.
 *
 * By hand because `java.net.URLEncoder` is not on `core`'s path by design and because its
 * form encoding is wrong here anyway: it writes a space as `+`, which arrives in a WhatsApp
 * composer as a literal plus sign in the middle of every sentence.
 */
private fun encode(text: String): String =
  buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
      // Masked to a byte before anything else. A Kotlin Byte is signed, so every character
      // outside ASCII - every accented letter, every emoji - arrives here as a negative
      // number, and both the ASCII test and the hex formatting give nonsense for those.
      val value = byte.toInt() and 0xFF
      val c = value.toChar()
      if (value < 0x80 && (c.isLetterOrDigit() || c in SAFE)) append(c)
      else append('%').append("%02X".format(value))
    }
  }

private const val SAFE = "-_.~"
private const val SHORTEST_NUMBER = 7
private const val SHORTEST_HANDLE = 4
