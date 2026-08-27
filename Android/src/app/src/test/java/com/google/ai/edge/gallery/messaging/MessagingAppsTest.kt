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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Working out which app was meant, and whether a number is safe to open a chat with.
 *
 * The second half is the one with teeth. wa.me has no notion of a local number: handed one, it
 * opens a conversation with whatever those digits turn out to be under some other country's code -
 * a message written to a stranger, addressed by name, ready to send. Refusing is the only
 * acceptable way to be unsure.
 */
class MessagingAppsTest {

  /** This arrives from a model repeating what a person said, so it has to be read generously. */
  @Test
  fun recognisesTheAppsHoweverTheyWereNamed() {
    assertEquals(MessagingApp.WHATSAPP, MessagingApps.fromSpokenName("whatsapp"))
    assertEquals(MessagingApp.WHATSAPP, MessagingApps.fromSpokenName("WhatsApp"))
    assertEquals(MessagingApp.WHATSAPP, MessagingApps.fromSpokenName("Whats App"))
    assertEquals(MessagingApp.WHATSAPP, MessagingApps.fromSpokenName("wa"))
    assertEquals(MessagingApp.TELEGRAM, MessagingApps.fromSpokenName("Telegram"))
    assertEquals(MessagingApp.TELEGRAM, MessagingApps.fromSpokenName("tg"))
  }

  /** No app named is the phone's own messaging app - what "text Sam" has always meant. */
  @Test
  fun treatsNoAppAsATextMessage() {
    assertEquals(MessagingApp.SMS, MessagingApps.fromSpokenName(""))
    assertEquals(MessagingApp.SMS, MessagingApps.fromSpokenName("   "))
    assertEquals(MessagingApp.SMS, MessagingApps.fromSpokenName("sms"))
    assertEquals(MessagingApp.SMS, MessagingApps.fromSpokenName("text"))
  }

  /**
   * RCS is not an app to choose. It is what the default messaging app upgrades a conversation to
   * when both ends support it, so asking for it means the same thing as asking for a text.
   */
  @Test
  fun treatsRcsAsTheMessagingApp() {
    assertEquals(MessagingApp.SMS, MessagingApps.fromSpokenName("RCS"))
  }

  /** An app that is not one of these must not be silently turned into a text to the wrong place. */
  @Test
  fun refusesAnAppItCannotWriteIn() {
    assertNull(MessagingApps.fromSpokenName("Signal"))
    assertNull(MessagingApps.fromSpokenName("Messenger"))
  }

  @Test
  fun acceptsAnInternationalNumber() {
    assertEquals("447700900123", MessagingApps.whatsAppNumber("+44 7700 900123"))
    assertEquals("447700900123", MessagingApps.whatsAppNumber("+447700900123"))
  }

  /** Dialled international prefixes mean the same thing as a plus. */
  @Test
  fun acceptsTheDialledInternationalPrefix() {
    assertEquals("447700900123", MessagingApps.whatsAppNumber("00447700900123"))
  }

  /** The case that would write to a stranger. */
  @Test
  fun refusesALocalNumber() {
    assertNull(MessagingApps.whatsAppNumber("07700 900123"))
    assertNull(MessagingApps.whatsAppNumber("5551234"))
  }

  /** Too short or too long to be a real number, whatever the plus says. */
  @Test
  fun refusesSomethingThatCannotBeANumber() {
    assertNull(MessagingApps.whatsAppNumber("+123"))
    assertNull(MessagingApps.whatsAppNumber("+1234567890123456789"))
    assertNull(MessagingApps.whatsAppNumber(""))
  }
}
