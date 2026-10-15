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

package com.lin.agent.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lin.agent.core.apps.appNamed
import com.lin.agent.core.screen.ScreenNode
import com.lin.agent.core.screen.chatLink
import com.lin.agent.core.screen.composerField
import com.lin.agent.core.screen.maySendItself
import com.lin.agent.core.screen.recipeFor
import com.lin.agent.core.screen.sendButton
import com.lin.agent.core.screen.soundsLikeSendIt
import com.lin.agent.core.screen.tapPolicyNamed
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.screen.LinAccessibility
import com.lin.agent.screen.accessibilityGranted
import com.lin.agent.screen.openAccessibilitySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Writes a message in another app's own composer.
 *
 * The thing the notification reply could never do. [ReplyTool] answers a message that is
 * already on screen, through the reply action the notification carries; this starts a
 * conversation - which no public API on Android allows at all. The only way is the way Tasker
 * and AutoInput do it: an accessibility service that reads the screen and acts on it.
 *
 * Two safeguards, and they are the reason this is not simply "type and press":
 *
 *  - **The message is typed, not sent.** Lin presses Send only when the person has chosen that
 *    in settings - see [com.lin.agent.core.screen.TapPolicy] - and the middle setting asks
 *    whether *their own words* were an instruction to send, read from what they actually said
 *    rather than from the model asserting it. A 2B model asked "did they want it sent?" will
 *    say yes, because that is the helpful answer, and this action has no undo.
 *  - **The button is chosen in `core`, with tests.** The microphone button sits exactly where
 *    the send button sits in WhatsApp, Telegram and Signal alike, and swaps with it depending
 *    on whether the box has text. Pressing it records audio and sends a voice note. See
 *    [sendButton] and the test that pins it.
 */
class SendMessageTool(
  private val context: Context,
  private val settings: SettingsRepository,
  private val guard: LaunchGuard,
  /**
   * What the person last said, for the "only when they asked" setting.
   *
   * A provider rather than an argument on the tool. The model must not be the one who decides
   * that sending was asked for, so this reads the conversation instead - the sentence the
   * person actually typed or spoke.
   */
  private val lastSaid: () -> String,
) : AgentTool {

  override val name = "send_message"

  override val description =
    "Write a message to somebody in a messaging app - WhatsApp, Telegram, Signal, Messages. " +
      "It is typed into the app's own box. Say whether it was sent or is waiting to be sent, " +
      "using exactly what this returns."

  override val parameters =
    listOf(
      ToolParameter(
        name = "app",
        type = ParamType.STRING,
        description = "Which app: whatsapp, telegram, signal, messages.",
      ),
      ToolParameter(
        name = "to",
        type = ParamType.STRING,
        description =
          "Who it is for. A phone number in full international form works best; for Telegram, " +
            "an @handle.",
      ),
      ToolParameter(
        name = "text",
        type = ParamType.STRING,
        description = "Exactly what the message should say.",
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val wantedApp = (arguments["app"] as? String)?.trim().orEmpty()
    val to = (arguments["to"] as? String)?.trim().orEmpty()
    val text = (arguments["text"] as? String)?.trim().orEmpty()

    if (text.isEmpty()) return ToolResult.Failed("There was no message to write. Ask what to say.")
    if (wantedApp.isEmpty()) return ToolResult.Failed("No app was named. Ask which one.")

    val installed = installedApps(context)
    val app =
      appNamed(wantedApp, installed)
        ?: return ToolResult.Failed(
          "There is no app called '$wantedApp' on this phone. Say so, and do not try another."
        )

    if (!accessibilityGranted(context)) return askForAccessibility()
    val service =
      LinAccessibility.live
        ?: return ToolResult.Failed(
          "Lin's screen access is switched on but not running yet - the phone has not bound it. " +
            "Say that it should work in a moment."
        )

    // The link is worth a lot more than the typing: it lands on the right conversation with
    // the words already in the box, so the step that could reach the wrong person never
    // happens. Null means this app has no such link, and then the only safe thing is to work
    // with the conversation the person already has open.
    val link = chatLink(app.packageName, to, text)
    if (link != null) {
      if (!open(link, app.packageName)) {
        return ToolResult.Failed("${app.label} would not open that conversation.")
      }
    } else if (service.frontApp() != app.packageName) {
      return ToolResult.Failed(
        "Lin cannot open a particular conversation in ${app.label} - only WhatsApp, Telegram " +
          "and Messages have a link for that, and Telegram needs an @handle. Ask the user to " +
          "open the chat in ${app.label} themselves and then say 'type it'."
      )
    }

    val field = waitForComposer(service, app.packageName) ?: return noComposer(app.label)

    // The link may have filled the box already, in which case typing again would double the
    // message or replace a longer one the person was in the middle of.
    val typed =
      if (field.text.trim() == text) true else service.type(field, text)
    if (!typed) {
      return ToolResult.Failed(
        "Lin could not type into ${app.label}. Nothing was written and nothing was sent."
      )
    }

    val policy = tapPolicyNamed(runCatching { settings.tapSend.first() }.getOrNull())
    if (!maySendItself(policy, soundsLikeSendIt(lastSaid()))) {
      return ToolResult.Ok(staged(app.label, text))
    }

    val button = sendButton(service.nodes(), recipeFor(app.packageName))
    if (button == null || !service.press(button)) {
      Log.i(TAG, "No send button found in ${app.packageName}")
      return ToolResult.Ok(
        "Done - the message is typed in ${app.label} but Lin could not find the send button, " +
          "so nothing has been sent. Tell the user it is waiting for them to tap send, and " +
          "read them the wording: \"$text\""
      )
    }

    return ToolResult.Ok("Done - the message was sent in ${app.label}. It said: \"$text\"")
  }

  private fun staged(label: String, text: String) =
    "Done - the message is typed into ${label}'s own message box and is NOT sent. Lin does " +
      "not press send unless that is turned on in settings. Tell the user it is waiting for " +
      "them to tap send, and read the wording back so they know what is about to go: \"$text\""

  private fun noComposer(label: String) =
    ToolResult.Failed(
      "Lin opened $label but could not find one message box to type into, so nothing was " +
        "written. That happens when the chat has not opened yet or when the screen has two " +
        "boxes on it - Lin will not guess which, because typing into the search box and " +
        "sending goes to the wrong person."
    )

  /**
   * Waits for the conversation to be in front with a box to type into.
   *
   * Polled rather than driven by an accessibility event, because the event this would wait for
   * is the one the service deliberately ignores - it collects nothing. A few hundred
   * milliseconds of polling after a launch is the cost of not keeping a record of everything
   * that crosses the screen.
   */
  private suspend fun waitForComposer(
    service: LinAccessibility,
    packageName: String,
  ): ScreenNode? {
    val recipe = recipeFor(packageName)
    repeat(TRIES) {
      if (service.frontApp() == packageName) {
        composerField(service.nodes(), recipe)?.let { return it }
      }
      delay(EACH_MS)
    }
    return null
  }

  private fun open(link: String, packageName: String): Boolean {
    val uri = Uri.parse(link)
    val action = if (uri.scheme == "smsto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW
    val intent = Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Aimed at the app rather than left to the chooser. A wa.me link with no package opens a
    // browser, which then offers to open WhatsApp - two screens, and the words lost on the way.
    return runCatching { context.startActivity(intent.setPackage(packageName)) }.isSuccess ||
      runCatching { context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .isSuccess
  }

  private fun askForAccessibility(): ToolResult {
    // Guarded like the settings panels are: an honest "not done yet" reads to a small model as
    // a failure worth retrying, and three accessibility screens stacking up is what that looks
    // like from the outside.
    if (guard.allow("accessibility", System.currentTimeMillis())) {
      openAccessibilitySettings(context)
    }
    return ToolResult.Failed(
      "Lin cannot type in other apps yet. Writing a message in an app's own box needs Lin's " +
        "screen access, which is granted by hand in the phone's accessibility settings - the " +
        "screen is now open. Nothing was written. Say that, and say it only has to be done once."
    )
  }

  private companion object {
    const val TAG = "LinSendMessage"
    const val TRIES = 20
    const val EACH_MS = 250L
  }
}
