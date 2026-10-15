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

/**
 * Whether handing work to the server is worth anything this turn.
 *
 * The whole point of delegation is the *difference* between two models: a small one on the
 * phone that holds the conversation, and a bigger one on a server that does the heavy piece.
 * When the server model is already the one answering, that difference is zero - Lin would
 * open a second connection to the same server, send the same model a restatement of the
 * question it is in the middle of, wait for it, and then repeat the answer. Slower than not
 * delegating, and the tool card on screen says a tool ran for nothing.
 *
 * Three conditions, and each rules out a case that really happens:
 *
 *  - **Nothing configured.** No server, no model: the tool can only fail. This was the first
 *    version of this rule, and it was not enough.
 *  - **The server is answering.** The same model on both ends. This is the case this file was
 *    written for.
 *  - **The server was meant to answer and is not.** `answerOnServer` is on but the phone is
 *    replying, which only happens when the server could not be reached - so a delegation to
 *    it would fail too, after a timeout the person waits through.
 */
fun delegationIsWorthIt(
  /** A server address and a model name are both configured. */
  hasServerModel: Boolean,
  /** The user asked for the server to hold the conversation. */
  wantsServerToAnswer: Boolean,
  /** The backend actually answering this turn is the server one. */
  serverIsAnswering: Boolean,
): Boolean = hasServerModel && !serverIsAnswering && !wantsServerToAnswer
