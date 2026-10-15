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
 * Where one app keeps its message box and its send button.
 *
 * Hints, not a specification. Another app's view ids are its own business and change when it
 * is updated, so nothing here is relied on: a recipe that matches nothing falls through to the
 * general rules in [composerField] and [sendButton], which is how an app with no entry here
 * works at all. What a recipe buys is the screens where the general rule would have to decline
 * - two editable boxes, or a send button with no readable label.
 */
data class ComposerRecipe(
  val packageName: String,
  /** Fragments of the id of the box a message is typed into, best first. */
  val fieldIds: List<String> = emptyList(),
  /** Fragments of the id of the button that sends it. */
  val sendIds: List<String> = emptyList(),
)

/**
 * The recipe for an app, or an empty one.
 *
 * An empty recipe is a working recipe. It means "use the general rules", which handle the
 * ordinary case - one editable box, a button labelled Send - and decline the ambiguous one.
 */
fun recipeFor(packageName: String): ComposerRecipe =
  KNOWN_COMPOSERS.firstOrNull { it.packageName == packageName }
    ?: ComposerRecipe(packageName)

/**
 * The apps worth knowing about, which is to say the ones people actually message in.
 *
 * Telegram is listed with no ids on purpose rather than left out: it draws custom views with no
 * resource names at all and labels them for screen readers, so its send button is found by its
 * description and its message box by being the only editable one. Having the entry says that
 * was checked rather than forgotten.
 */
val KNOWN_COMPOSERS =
  listOf(
    ComposerRecipe(
      packageName = "com.whatsapp",
      fieldIds = listOf(":id/entry"),
      sendIds = listOf(":id/send"),
    ),
    ComposerRecipe(packageName = "com.whatsapp.w4b", fieldIds = listOf(":id/entry"), sendIds = listOf(":id/send")),
    ComposerRecipe(packageName = "org.telegram.messenger"),
    ComposerRecipe(packageName = "org.telegram.messenger.web"),
    ComposerRecipe(
      packageName = "org.thoughtcrime.securesms",
      fieldIds = listOf("embedded_text_editor", "compose_text"),
      sendIds = listOf("send_button"),
    ),
    ComposerRecipe(
      packageName = "com.google.android.apps.messaging",
      fieldIds = listOf("compose_message_text"),
      sendIds = listOf("send_message_button"),
    ),
    ComposerRecipe(packageName = "com.samsung.android.messaging"),
    ComposerRecipe(packageName = "com.facebook.orca"),
    ComposerRecipe(packageName = "org.thoughtcrime.securesms.beta"),
  )
