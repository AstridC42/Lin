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

package com.google.ai.edge.gallery.messaging

/** A messaging app the assistant can hand a message to. */
enum class MessagingApp {
  /**
   * The phone's own messaging app.
   *
   * RCS needs no separate case: it is not a protocol an app chooses, it is what the default
   * messaging app upgrades a conversation to when both ends support it. Handing the message to that
   * app is what sends it over RCS where RCS exists and over SMS where it does not.
   */
  SMS,
  WHATSAPP,
  TELEGRAM,
}

/**
 * Working out which app was meant, and getting a message into it.
 *
 * None of these can be sent outright. WhatsApp and Telegram publish no API for a third-party app to
 * send on someone's behalf, and the deep links they do publish open the conversation with the text
 * already written, for the sender to send. That is the same bargain the SMS composer has always
 * made here, and it is the right one anyway: a message is the one action on a phone that reaches
 * another person and cannot be taken back.
 *
 * The one case that does send outright is replying to a notification that is already on screen,
 * which is a different mechanism entirely and lives in the notifications tool.
 */
object MessagingApps {

  const val WHATSAPP_PACKAGE = "com.whatsapp"
  const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

  /** Telegram has several builds and forks, all of which accept the same share intent. */
  val TELEGRAM_PACKAGES =
    listOf(
      "org.telegram.messenger",
      "org.telegram.messenger.web",
      "org.telegram.plus",
      "org.thunderdog.challegram",
    )

  /**
   * Which app [value] names, or null when it names none of them.
   *
   * Generous about what counts, because this arrives from a model repeating what a person said:
   * "whatsapp", "on WhatsApp", "wa" and "Whats App" are all the same request. An empty value means
   * no app was named, which is the phone's own messaging app - what "text Sam" has always meant.
   */
  fun fromSpokenName(value: String): MessagingApp? {
    val cleaned = value.trim().lowercase().replace(Regex("[^a-z]"), "")
    return when {
      cleaned.isEmpty() -> MessagingApp.SMS
      cleaned in setOf("sms", "text", "message", "messages", "rcs", "mms", "default") ->
        MessagingApp.SMS
      cleaned.startsWith("whats") || cleaned == "wa" -> MessagingApp.WHATSAPP
      cleaned.startsWith("telegram") || cleaned == "tg" -> MessagingApp.TELEGRAM
      else -> null
    }
  }

  /**
   * A phone number in the form wa.me expects: digits only, country code included, no plus.
   *
   * Returns null rather than a guess when the number is not already international. wa.me has no
   * notion of a local number - given "07700 900123" it opens a conversation with whatever that is
   * in the country WhatsApp happens to assume, which is a message to a stranger, so not sending is
   * the only acceptable failure.
   */
  fun whatsAppNumber(e164OrRaw: String): String? {
    val digits = e164OrRaw.filter { it.isDigit() }
    if (!e164OrRaw.trim().startsWith("+") && !e164OrRaw.trim().startsWith("00")) return null
    val withoutTrunk = if (e164OrRaw.trim().startsWith("00")) digits.removePrefix("00") else digits
    // Shortest international numbers are around eight digits with the country code; longest are
    // fifteen by the ITU's own limit.
    return withoutTrunk.takeIf { it.length in 8..15 }
  }
}
