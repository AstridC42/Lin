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

package com.lin.agent.engine

import kotlinx.coroutines.flow.Flow

/** One message in a conversation. */
data class Message(val role: Role, val text: String) {
  enum class Role { USER, ASSISTANT, SYSTEM }
}

/** What a backend can do, so callers can ask rather than assume. */
data class BackendCapabilities(
  val supportsTools: Boolean,
  val supportsImages: Boolean,
  val supportsAudio: Boolean,
  /** Tokens, total, including whatever is put in front of the conversation. */
  val contextWindow: Int,
)

/** What comes back while a turn is running. */
sealed interface TurnEvent {
  /** A fragment of the reply, for streaming into the UI. */
  data class Token(val text: String) : TurnEvent

  /** The turn finished normally. */
  data class Done(val fullText: String) : TurnEvent

  /**
   * The turn failed, with something a person can act on.
   *
   * [detail] keeps the engine's own words, because on a phone with no computer attached it is the
   * only account of the failure that exists. [message] is what to show first.
   */
  data class Failed(val message: String, val detail: String = "") : TurnEvent {

    /**
     * The whole failure as one block of text to show.
     *
     * The detail is kept underneath the sentence because on a phone with no computer attached it
     * is the only account of the failure there is - but only when it adds something. An
     * unrecognised engine error explains itself as its own raw message, so [message] and
     * [detail] come out identical, and printing both put the same line on screen twice. That
     * happened on the two failures this app has actually shown a user, which is not a good
     * record for a one-line check.
     */
    val display: String
      get() =
        if (detail.isBlank() || detail.trim() == message.trim()) message
        else "$message\n\n$detail"
  }
}

/** What to run, and how. */
data class TurnRequest(
  val messages: List<Message>,
  val systemPrompt: String = "",
  /** Greedy by default. Sampling at temperature while emitting an exact grammar is how a tool
   * call comes out malformed, which cost the previous app several days. */
  val temperature: Double = 0.0,
  val topK: Int = 1,
)

/**
 * Every model reaches this app through here, local or remote.
 *
 * This is the architectural heart, and the reason the previous app had to be rewritten rather than
 * tidied. With one interface, "answer on the phone" and "hand it to a bigger model on the server"
 * stop being different code paths anywhere above this line - they are two implementations, chosen
 * per turn. Without it, remote inference is a special case threaded through everything.
 *
 * Three implementations are planned: on-device through LiteRT-LM, remote through Ollama, and
 * [FakeBackend] for tests. That third one is not an afterthought; it is what makes the agent loop
 * testable without a model, which the previous design made effectively impossible.
 */
interface LlmBackend {
  /** Stable, short, and shown to the user when it matters which backend answered. */
  val id: String

  val capabilities: BackendCapabilities

  /**
   * Whether this backend can be used right now.
   *
   * Not a formality for the remote one. Away from home the server does not exist, and an
   * assistant that works at home and fails on the train loses trust faster than one that is
   * merely slow - so reachability is part of the interface rather than an error discovered
   * halfway through a turn.
   */
  suspend fun reachable(): Boolean

  fun converse(request: TurnRequest): Flow<TurnEvent>
}
