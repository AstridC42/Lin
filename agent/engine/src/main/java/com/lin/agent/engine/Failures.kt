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
 * Whether a turn failed because the model wrote a tool call the engine could not read.
 *
 * It is worth telling apart from every other failure for one reason: it is the only one the
 * model itself can fix, and it usually does on the second attempt. What the engine reports
 * looks like this, and the shape of it is the model's rather than the engine's:
 *
 * ```
 * Failed to parse tool calls from code block:
 *   call:find_photo{describe:<|"|>a computer case…<|"|>, with:<|"|>,look:true}
 * ```
 *
 * A 2B model emitting the formatter's own quote markers inside the arguments, inventing a
 * parameter the tool does not have, and closing the block with the opening tag. Nothing in
 * this app produced that and nothing in this app can prevent it; what it can do is not throw
 * the turn away over it.
 *
 * Both halves are searched because the engine puts a readable sentence in one and the parser's
 * own words in the other, and which carries the evidence depends on where it failed.
 */
fun isMalformedToolCall(message: String, detail: String = ""): Boolean {
  val text = "$message $detail".lowercase()
  return text.contains("failed to parse tool calls") ||
    text.contains("parse fc tool calls") ||
    text.contains("wrote the request wrongly")
}

/**
 * What to tell the model after one of those, so the second attempt is different from the first.
 *
 * Specific rather than "that failed, try again": the mistakes are always the same three, and a
 * small model given the general form of the complaint repeats the exact error it just made.
 */
const val MALFORMED_TOOL_CALL_NUDGE: String =
  "Your last tool call could not be read, so nothing ran. Write it again as an ordinary tool " +
    "call: only the parameter names that tool actually has, plain text for every value, no " +
    "quote markers or tags of your own inside it. If you cannot, answer without the tool and " +
    "say what you could not do."
