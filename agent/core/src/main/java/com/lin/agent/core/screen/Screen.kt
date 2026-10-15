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
 * One thing on another app's screen, reduced to what choosing needs.
 *
 * A plain data class rather than Android's `AccessibilityNodeInfo` so that the deciding - which
 * box is the composer, which button sends, which button must never be pressed - is ordinary
 * code with ordinary tests. The service in the app module turns the real tree into these and
 * does what it is told; everything that could press the wrong button is in this file.
 */
data class ScreenNode(
  /** The view's resource name, like `com.whatsapp:id/entry`. Often absent. */
  val id: String = "",
  val text: String = "",
  /** The content description - what a screen reader would say. Often the only label. */
  val description: String = "",
  val className: String = "",
  val editable: Boolean = false,
  val clickable: Boolean = false,
  val enabled: Boolean = true,
)

/**
 * The box to type a message into, or null when it cannot be told.
 *
 * Null rather than a best guess, and that is the rule that matters most here. Typing into the
 * wrong editable box is not a cosmetic mistake: on a messaging screen the other editable box
 * is the search field, and a message typed into search and then sent with the button next to
 * it goes to whoever the search happened to select. One ambiguous screen that Lin declines to
 * act on costs a sentence; one wrong guess costs somebody a message they did not write going
 * to somebody they did not choose.
 *
 * Order: the app's own known field first, then the only editable box if there is exactly one,
 * then nothing.
 */
fun composerField(nodes: List<ScreenNode>, recipe: ComposerRecipe): ScreenNode? {
  val typeable = nodes.filter { it.editable && it.enabled && !isSearch(it) }
  if (typeable.isEmpty()) return null

  recipe.fieldIds
    .forEach { fragment ->
      typeable.firstOrNull { it.id.contains(fragment, ignoreCase = true) }?.let { return it }
    }

  return typeable.singleOrNull()
}

/**
 * The send button, or null.
 *
 * [looksDangerous] is applied before anything else, and it is the half of this function worth
 * reading. In WhatsApp, Telegram and Signal alike the microphone button sits exactly where
 * the send button sits and swaps places with it depending on whether the box has text in it.
 * A rule that found "the round button at the end of the composer" would, on a screen that had
 * not caught up with the typing yet, start recording audio and send somebody a voice note.
 */
fun sendButton(nodes: List<ScreenNode>, recipe: ComposerRecipe): ScreenNode? {
  val pressable = nodes.filter { it.clickable && it.enabled && !looksDangerous(it) }
  if (pressable.isEmpty()) return null

  recipe.sendIds
    .forEach { fragment ->
      pressable.firstOrNull { it.id.contains(fragment, ignoreCase = true) }?.let { return it }
    }

  // No id to go on - Telegram draws its own views and labels them for screen readers instead.
  // The label has to *be* send rather than contain it: "Send location" and "Send a photo" are
  // both buttons that do something else with the message.
  return pressable.firstOrNull { saysSend(it.description) || saysSend(it.text) }
}

/**
 * Whether pressing this would do something other than send the message that was typed.
 *
 * Deliberately generous. Everything named here is a button that appears on a messaging screen,
 * next to or instead of the send button, and does something that cannot be taken back: records
 * and sends audio, places a call, attaches a file, deletes a conversation, blocks a person.
 * A name not on this list is not pressed either unless something positively identifies it as
 * send - this list is the second lock, not the first.
 */
fun looksDangerous(node: ScreenNode): Boolean = wordsOf(node).any { it in DANGEROUS }

private fun isSearch(node: ScreenNode): Boolean = "search" in wordsOf(node)

/**
 * Everything a node is labelled with, as whole words.
 *
 * Whole words rather than substrings, and it is not a nicety. `com.whatsapp:id/dynamic_send`
 * contains "mic"; `sticker_gallery` contains "ticker"; half the ids in an Android app contain
 * "pay" somewhere. A substring match on the dangerous list would quietly refuse to press
 * perfectly good send buttons, and the symptom - Lin types the message and then says it cannot
 * find the button - would look like the app having changed rather than like this list.
 *
 * Splitting on everything that is not a letter does the job: ids are separated by dots,
 * colons, slashes and underscores, and labels by spaces.
 */
private fun wordsOf(node: ScreenNode): Set<String> =
  "${node.id} ${node.description} ${node.text}"
    .lowercase()
    .split(NOT_A_LETTER)
    .filter { it.isNotEmpty() }
    .toSet()

private val NOT_A_LETTER = Regex("[^a-z]+")

/**
 * Whether a label is the word send and nothing else.
 *
 * Stripped of trailing punctuation and a trailing count, because a real send button is
 * labelled "Send", "send" or "Send message" - and "Send money", "Send location" and "Send
 * without sound" are three other buttons.
 */
private fun saysSend(label: String): Boolean {
  val said = label.trim().lowercase().trimEnd('.', '!', ',')
  return said == "send" || said == "send message" || said == "send now"
}

private val DANGEROUS =
  listOf(
    "voice",
    "record",
    "audio",
    "mic",
    "call",
    "video",
    "attach",
    "camera",
    "gallery",
    "sticker",
    "emoji",
    "gif",
    "delete",
    "block",
    "report",
    "mute",
    "money",
    "payment",
    "pay",
    "location",
  )
