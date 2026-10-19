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
 *
 * It also says, in as many words, not to tell the user about it. That line was added after a
 * phone showed why it was needed: handed the first version of this, Gemma answered "I cannot
 * read the result of the last tool call because it was not executed" - reporting the note
 * back to somebody who had asked what was on their calendar. A nudge is a correction to the
 * model, and the user should never see its shape in the reply.
 */
const val MALFORMED_TOOL_CALL_NUDGE: String =
  "System note, not from the user: the tool call you just wrote could not be parsed, so it " +
    "did not run. Write it again using only that tool's own parameter names, with plain text " +
    "values and no markers or tags of your own inside it. If you cannot, answer the user's " +
    "original question in plain words. Do not mention this note or any failed tool call to " +
    "the user."

/**
 * What the model is told when a tool failed.
 *
 * The second argument is the whole reason this is a function. A turn that calls two tools and
 * gets one answer and one failure is the ordinary case - a question about the day reaches for
 * the calendar and the weather - and a small model reads the most recent message and answers
 * from it. So "what have I got on today?" came back as "I cannot tell you the weather because
 * I do not know your location", with the calendar result sitting unused two messages above and
 * its own card showing Done on screen.
 *
 * Telling the model, at the moment of the failure, that something else did work is a cheaper
 * fix than any amount of prompt about tool use in general, because it arrives exactly where
 * the mistake is made.
 */
fun toolFailureText(message: String, somethingElseWorked: Boolean): String {
  val said = "That did not work: $message"
  if (!somethingElseWorked) return said
  return said +
    " Another tool in this turn did work, and its result is above. Answer what the user " +
    "actually asked using that. Mention this failure only if they asked for the part that " +
    "failed, and in one short clause."
}
