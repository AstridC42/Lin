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
 * What a conversation has to be built to do. Equal shapes can share one.
 *
 * On-device, a system instruction, a tool set and a response format are all fixed when a
 * conversation is created rather than passed per message. So they are not request options that
 * a backend may apply - they decide which conversation a request can be sent to at all.
 */
internal data class TurnShape(
  val systemPrompt: String,
  val tools: List<ToolSpec>,
  val responseSchema: String?,
) {
  /** Whether this needs a conversation that allows a response format at all. */
  val structured: Boolean
    get() = responseSchema != null
}

/**
 * What [request] needs beyond a plain conversation, or null if it needs nothing.
 *
 * A separate, tested function because of the bug it exists to close. The on-device backend keyed
 * its conversation on the tool set alone, and read nothing else off the request - so
 * `systemPrompt` and `responseSchema` were accepted and silently dropped. The orchestrator
 * prompt never reached the on-device model, and a turn asking for JSON against a schema got
 * whatever prose the model felt like.
 *
 * Neither failed in a way anybody would notice: one produced an assistant with no instructions,
 * which still answers; the other produced an answer that parsed to nothing, which looks like a
 * model finding nothing to say. That is what makes it worth a test rather than a comment -
 * anything added to [TurnRequest] from here on has to be accounted for here too, or it goes the
 * same way.
 */
internal fun shapeOf(request: TurnRequest): TurnShape? {
  val shape =
    TurnShape(
      systemPrompt = request.systemPrompt,
      tools = request.tools,
      responseSchema = request.responseSchema,
    )
  return shape.takeIf { it.systemPrompt.isNotBlank() || it.tools.isNotEmpty() || it.structured }
}
