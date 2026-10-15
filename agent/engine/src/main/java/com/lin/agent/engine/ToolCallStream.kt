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
 * One tool call, as a string that is equal for the same call and different for a different one.
 *
 * This exists as its own function because it was wrong, invisibly, and in the one way that a
 * dedupe can be wrong without ever looking wrong. The on-device backend built it inline with
 * the `$` of each interpolation escaped, so the fingerprint was the *constant text*
 * `${it.name}(${it.arguments})` for every call ever made. Deduplication still appeared to
 * work - the first call of an exchange went through and repeats of it did not - so the only
 * symptom was the one that is impossible to read: when the model asked for two different
 * tools in a single message, the second was dropped on the floor, the model was handed a
 * result for one call and nothing for the other, and it said so out loud: "I cannot read the
 * result of the last tool call because it was not executed."
 *
 * Pulled out here so it can be tested at all. The backend around it needs LiteRT-LM and a
 * phone; this needs neither, and it is the part that was broken.
 */
fun fingerprintOf(call: ToolInvocation): String = "${call.name}(${call.arguments})"

/**
 * Keeps the calls that have not been seen before, in order.
 *
 * Needed because a streaming engine reports tool calls on whichever delta carries them and may
 * repeat what an earlier delta already announced - and running a tool twice for one request is
 * worse than missing a duplicate. One of these lives for one exchange.
 */
class FreshToolCalls {

  private val seen = mutableSetOf<String>()

  fun fresh(calls: List<ToolInvocation>): List<ToolInvocation> =
    calls.filter { seen.add(fingerprintOf(it)) }
}
