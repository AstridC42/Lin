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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.lin.agent.car.inCar
import com.lin.agent.core.car.dialPolicyNamed
import com.lin.agent.core.car.mayDialDirectly
import com.lin.agent.core.phone.CallDecision
import com.lin.agent.core.phone.KnownNumber
import com.lin.agent.core.phone.decideCall
import com.lin.agent.core.phone.looksLikeANumber
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The phone itself: calling someone, and writing to them.
 *
 * Every action here is **staged** rather than completed. A call goes into the dialer and a
 * message goes into the messaging app with the text already in it, and in both cases the last
 * action belongs to the user. That is not timidity about a new feature; it is the only
 * arrangement where a model that misreads a name or transposes two digits costs a tap instead of
 * something that cannot be taken back.
 *
 * The single exception is direct calling, which exists because staging every call makes a
 * hands-free assistant useless - and it is reachable only by someone who turned it on knowing
 * what it does and granted the permission. The decision itself lives in
 * [com.lin.agent.core.phone.decideCall], away from the Android plumbing, so the rule can be
 * tested rather than inspected.
 *
 * A message is never sent. `ACTION_SENDTO` cannot send on its own - it opens the composer with
 * the text in it - so "messages wait for a send" is a property of the mechanism rather than a
 * promise made by this class.
 */
class PhoneTool(
  private val context: Context,
  private val settings: SettingsRepository,
) : AgentTool {

  override val name = "phone_action"

  override val description =
    "Call someone or write them a message. Both open on screen for the user to confirm - a " +
      "call goes to the dialer and a message to the messaging app with the text ready - so " +
      "neither completes on its own."

  override val parameters =
    listOf(
      ToolParameter(
        name = "action",
        type = ParamType.STRING,
        description = "Either 'call' or 'message'.",
      ),
      ToolParameter(
        name = "who",
        type = ParamType.STRING,
        description = "A contact name as the user said it, or a full phone number. Do not " +
          "invent a number: if you do not know it, pass the name.",
      ),
      ToolParameter(
        name = "text",
        type = ParamType.STRING,
        description = "What the message should say. Only for 'message'.",
        required = false,
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val who = (arguments["who"] as? String)?.trim().orEmpty()
    if (who.isEmpty()) return ToolResult.Failed("No one was named, so nothing was done.")

    return when ((arguments["action"] as? String)?.trim()?.lowercase()) {
      "call" -> call(who)
      "message" -> message(who, (arguments["text"] as? String).orEmpty())
      else ->
        ToolResult.Failed("The action must be \"call\" or \"message\".")
    }
  }

  private suspend fun call(who: String): ToolResult {
    val decision =
      decideCall(
        requested = who,
        matches = lookUp(who),
        addressBook = if (looksLikeANumber(who)) everyone() else emptyList(),
        // Where, not whether: the middle setting dials straight out in a car and stages it
        // everywhere else, because the tap that makes staging safe is the one thing nobody
        // should be doing while driving.
        optedInToDirectCalls =
          mayDialDirectly(dialPolicyNamed(settings.dialPolicy.first()), inCar(context)),
        callPermissionGranted = granted(Manifest.permission.CALL_PHONE),
      )

    return when (decision) {
      is CallDecision.Refuse -> ToolResult.Failed(decision.reason)
      is CallDecision.AskFirst -> ToolResult.Failed(decision.question)
      is CallDecision.PlaceCall ->
        start(Intent(Intent.ACTION_CALL, telUri(decision.number)))
          ?.let { ToolResult.Failed(it) }
          ?: ToolResult.Ok("Calling ${decision.displayName} on ${decision.number} now.")
      is CallDecision.OpenDialer ->
        start(Intent(Intent.ACTION_DIAL, telUri(decision.number)))
          ?.let { ToolResult.Failed(it) }
          ?: ToolResult.Ok(
            "The dialer is open with ${decision.displayName}'s number " +
              "(${decision.number}) in it; the user taps to call. ${decision.why}"
          )
    }
  }

  private suspend fun message(who: String, text: String): ToolResult {
    if (text.isBlank()) {
      return ToolResult.Failed("There was no message to write. Ask the user what to say.")
    }

    // The same resolution a call gets, and for the same reason: a message to the wrong person is
    // not as bad as a call to one, but it is still not retrievable.
    val decision =
      decideCall(
        requested = who,
        matches = lookUp(who),
        addressBook = if (looksLikeANumber(who)) everyone() else emptyList(),
        // Messaging is always staged, so the direct-call authorisations are irrelevant here and
        // are passed as false rather than read - there is no path from this method to a sent
        // message, whatever the user has turned on for calls.
        optedInToDirectCalls = false,
        callPermissionGranted = false,
      )

    val (number, displayName) =
      when (decision) {
        is CallDecision.Refuse -> return ToolResult.Failed(decision.reason)
        is CallDecision.AskFirst -> return ToolResult.Failed(decision.question)
        is CallDecision.OpenDialer -> decision.number to decision.displayName
        is CallDecision.PlaceCall -> decision.number to decision.displayName
      }

    val intent =
      Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text)
    return start(intent)?.let { ToolResult.Failed(it) }
      ?: ToolResult.Ok(
        "The message to $displayName is written out and waiting on screen. It has not been " +
          "sent - the user presses send. Read the wording back to them so they know what is " +
          "about to go."
      )
  }

  /** Starts [intent], returning null on success or a sentence on failure. */
  private fun start(intent: Intent): String? =
    try {
      // From a tool rather than an activity, so the task flag is required.
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      null
    } catch (e: Throwable) {
      Log.w(TAG, "Could not start ${intent.action}", e)
      "This phone has no app that can do that."
    }

  private fun telUri(number: String): Uri = Uri.parse("tel:" + Uri.encode(number))

  private fun granted(permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  /** Contacts matching what the user called someone, or empty if there is no permission. */
  private suspend fun lookUp(name: String): List<KnownNumber> {
    if (looksLikeANumber(name)) return emptyList()
    return query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
        .buildUpon()
        .appendPath(name)
        .build()
    )
  }

  /**
   * Everybody with a number, for the near-miss check.
   *
   * Only read when the model supplied raw digits, which is the only case where a transposition
   * is possible - looking up the whole address book to dial a contact the user named would be
   * reading everything to learn nothing.
   */
  private suspend fun everyone(): List<KnownNumber> =
    query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

  private suspend fun query(uri: Uri): List<KnownNumber> =
    withContext(Dispatchers.IO) {
      if (!granted(Manifest.permission.READ_CONTACTS)) return@withContext emptyList()
      val columns =
        arrayOf(
          ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
          ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
      runCatching {
          context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
              while (cursor.moveToNext() && size < MAX_CONTACTS) {
                val person = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                if (person.isNotEmpty() && number.isNotEmpty()) add(KnownNumber(person, number))
              }
            }
          }
        }
        .onFailure { Log.w(TAG, "Could not read contacts", it) }
        .getOrNull()
        .orEmpty()
    }

  private companion object {
    const val TAG = "LinPhoneTool"

    /**
     * A ceiling on the near-miss scan.
     *
     * An address book can hold thousands, and every one of them is compared against the digits
     * the model produced. The cap keeps a call from pausing noticeably; a transposition of a
     * number that far down the list is a case this does not catch, which is a smaller cost than
     * the delay.
     */
    const val MAX_CONTACTS = 2000
  }
}
