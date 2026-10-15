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

/** Whether Lin may press Send itself, and when. */
enum class TapPolicy {
  /** Never. Lin types the message and leaves it on screen. The default. */
  NEVER,

  /** Only when the person's own words asked for it to be sent. */
  WHEN_ASKED,

  /** Always, once a message has been written. */
  ALWAYS,
}

fun tapPolicyNamed(name: String?): TapPolicy =
  TapPolicy.entries.firstOrNull { it.name == name?.trim()?.uppercase() } ?: TapPolicy.NEVER

/**
 * Whether to press Send.
 *
 * The same rule as placing a call, and for the same reason: a sent message cannot be unsent.
 * The difference from a call is that this one is cheap to stage - the words are in the
 * composer, in the real app, with the real send button under the person's thumb - so the
 * default costs one tap and buys the guarantee that nothing Lin misheard ever left the phone.
 *
 * [askedToSend] is the person's own words, not the model's intention. "Tell Dad I'm late" is a
 * request to write and send; "draft something to Dad" is not. The caller decides which from
 * what was actually said - see [soundsLikeSendIt] - rather than from the model asserting it,
 * because a model that wants to be helpful will assert it every time.
 */
fun maySendItself(policy: TapPolicy, askedToSend: Boolean): Boolean =
  when (policy) {
    TapPolicy.NEVER -> false
    TapPolicy.WHEN_ASKED -> askedToSend
    TapPolicy.ALWAYS -> true
  }

/**
 * Whether what the person said was "send it" rather than "write it".
 *
 * Read from their words rather than taken from the model, which is the whole point. A 2B model
 * asked whether the user wanted the message sent will say yes, because that is the helpful
 * answer, and on an action with no undo the helpful answer is not good enough.
 *
 * Conservative on purpose: anything that is not clearly an instruction to send reads as a
 * draft, and a draft costs one tap.
 */
fun soundsLikeSendIt(said: String): Boolean {
  val words = said.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }
  if (words.isEmpty()) return false
  // Asked for a draft, whatever else is in the sentence. "Draft a message to Dad and send it"
  // is somebody thinking aloud, and the safe reading of a sentence that says both is the one
  // that leaves the words on screen.
  if (words.any { it in DRAFTING }) return false
  return words.any { it in SENDING }
}

private val SENDING = setOf("send", "sends", "sent", "text", "message", "tell", "reply", "answer")
private val DRAFTING =
  setOf("draft", "write", "compose", "prepare", "suggest", "wording", "don't", "dont", "without")
private val NOT_A_WORD = Regex("[^a-z0-9']+")
