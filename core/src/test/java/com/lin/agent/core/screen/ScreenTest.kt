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

class ScreenTest {

  private val whatsapp = recipeFor("com.whatsapp")
  private val unknown = recipeFor("com.example.chat")

  private fun field(id: String, text: String = "") =
    ScreenNode(id = id, text = text, editable = true, className = "android.widget.EditText")

  private fun button(id: String = "", description: String = "", text: String = "") =
    ScreenNode(id = id, description = description, text = text, clickable = true)

  @Test
  fun `the app's own message box is found among several`() {
    val nodes =
      listOf(
        field("com.whatsapp:id/search_src_text"),
        field("com.whatsapp:id/entry"),
      )
    assertEquals("com.whatsapp:id/entry", composerField(nodes, whatsapp)?.id)
  }

  @Test
  fun `the only editable box is the message box even in an app with no recipe`() {
    val nodes = listOf(button(text = "Back"), field("com.example.chat:id/whatever"))
    assertEquals("com.example.chat:id/whatever", composerField(nodes, unknown)?.id)
  }

  @Test
  fun `two unidentified boxes is not a guess`() {
    // The rule the whole file is built around. The other editable box on a messaging screen is
    // the search field, and a message typed into search and sent with the button beside it
    // goes to whoever search happened to select.
    val nodes = listOf(field("com.example.chat:id/one"), field("com.example.chat:id/two"))
    assertNull(composerField(nodes, unknown))
  }

  @Test
  fun `a search box is never typed into`() {
    val nodes = listOf(field("com.example.chat:id/search_input"))
    assertNull(composerField(nodes, unknown))
  }

  @Test
  fun `a disabled box is not typed into`() {
    val nodes = listOf(field("com.whatsapp:id/entry").copy(enabled = false))
    assertNull(composerField(nodes, whatsapp))
  }

  @Test
  fun `nothing editable means nothing to type into`() {
    assertNull(composerField(listOf(button(text = "Send")), whatsapp))
    assertNull(composerField(emptyList(), whatsapp))
  }

  @Test
  fun `the app's own send button wins`() {
    val nodes = listOf(button(id = "com.whatsapp:id/voice_note_btn"), button(id = "com.whatsapp:id/send"))
    assertEquals("com.whatsapp:id/send", sendButton(nodes, whatsapp)?.id)
  }

  @Test
  fun `a send button with no id is found by its label`() {
    // Telegram draws its own views with no resource names and labels them for screen readers.
    val nodes = listOf(button(description = "Emoji"), button(description = "Send"))
    assertEquals("Send", sendButton(nodes, recipeFor("org.telegram.messenger"))?.description)
  }

  @Test
  fun `the microphone is never pressed`() {
    // It sits exactly where the send button sits and swaps with it depending on whether the
    // box has text. Pressing it starts recording and sends a voice note.
    val nodes = listOf(button(id = "com.whatsapp:id/voice_note_btn", description = "Voice message"))
    assertNull(sendButton(nodes, whatsapp))
  }

  @Test
  fun `send money and send location are not the send button`() {
    val nodes = listOf(button(description = "Send money"), button(description = "Send location"))
    assertNull(sendButton(nodes, unknown))
  }

  @Test
  fun `a plain send label is accepted in several forms`() {
    for (label in listOf("Send", "send", "SEND", "Send message", "Send now", "Send.")) {
      assertEquals(
        "did not accept '$label'",
        label,
        sendButton(listOf(button(description = label)), unknown)?.description,
      )
    }
  }

  @Test
  fun `a dangerous button is rejected even when its id matches the recipe`() {
    // The order matters: the danger list is applied before the recipe, so an app that renames
    // its microphone to something containing "send" cannot get it pressed.
    val nodes = listOf(button(id = "com.whatsapp:id/send_voice_note"))
    assertNull(sendButton(nodes, whatsapp))
  }

  @Test
  fun `an id that merely contains a dangerous word as a fragment is still pressable`() {
    // "dynamic" contains "mic"; "sticker_gallery" contains "ticker". A substring match on the
    // danger list would refuse perfectly good send buttons, and the symptom - Lin types the
    // message and then cannot find the button - would read as the app having changed.
    val nodes = listOf(button(id = "com.example.chat:id/dynamic_send", description = "Send"))
    assertEquals("com.example.chat:id/dynamic_send", sendButton(nodes, unknown)?.id)
    assertFalse(looksDangerous(ScreenNode(id = "com.example.chat:id/dynamic_send")))
    assertFalse(looksDangerous(ScreenNode(id = "com.example.chat:id/payload_send")))
  }

  @Test
  fun `the danger words are caught as whole words wherever they appear`() {
    assertTrue(looksDangerous(ScreenNode(description = "Voice message")))
    assertTrue(looksDangerous(ScreenNode(id = "org.example:id/attach_button")))
    assertTrue(looksDangerous(ScreenNode(text = "Delete chat")))
    assertTrue(looksDangerous(ScreenNode(description = "Video call")))
  }

  @Test
  fun `a button that is not clickable or not enabled is not the send button`() {
    assertNull(sendButton(listOf(ScreenNode(description = "Send")), unknown))
    assertNull(sendButton(listOf(button(description = "Send").copy(enabled = false)), unknown))
  }

  @Test
  fun `an app with no entry gets a working empty recipe`() {
    val recipe = recipeFor("com.example.chat")
    assertEquals("com.example.chat", recipe.packageName)
    assertTrue(recipe.fieldIds.isEmpty())
    assertTrue(recipe.sendIds.isEmpty())
  }

  @Test
  fun `no two recipes claim the same package`() {
    val names = KNOWN_COMPOSERS.map { it.packageName }
    assertEquals("a duplicate recipe can never be reached", names.size, names.distinct().size)
  }
}
