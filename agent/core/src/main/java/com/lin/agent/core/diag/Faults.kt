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

package com.lin.agent.core.diag

/**
 * A failure in one clause, for the middle of a sentence on screen.
 *
 * Written because the alternative is what the app used to do with an unexpected fault: nothing,
 * and then close. Showing something is strictly better, but only if what is shown can be read
 * by the person it closed on - and the raw text of a Java exception mostly cannot. Two cases
 * carry the file:
 *
 *  - **No message at all.** `NullPointerException` and `IndexOutOfBoundsException` often carry
 *    nothing, and `": null"` on the end of a sentence is worse than saying there was a fault.
 *  - **A message that is true and useless.** An out-of-memory from the engine says "Failed to
 *    allocate a 71303168 byte allocation", which tells somebody holding a phone nothing about
 *    what to do. It is the single most likely fault here, because the thing this app does is
 *    load a two-gigabyte model, so it gets a sentence of its own.
 *
 * Takes the type as a string rather than a Throwable so it is testable without constructing
 * exceptions, and so this stays in `core` with no dependency on how the caller caught it.
 */
fun describeFault(type: String?, message: String?): String {
  val kind = type.orEmpty().trim()
  val said = message.orEmpty().trim()

  if (kind.contains("OutOfMemory", ignoreCase = true)) {
    return "the phone ran out of memory, which usually means the model is too large for it"
  }

  val text = said.ifBlank { kind }
  if (text.isBlank()) return "an unknown fault"

  // Trailing punctuation is removed because this clause is dropped into the middle of a
  // sentence: "went wrong: it broke.. Nothing was lost" is the version without this line.
  val tidy = text.trimEnd('.', ' ', '\n')
  if (tidy.length <= LONGEST) return tidy

  // A native error can be a page of stack. The whole thing belongs in the report, not in a
  // card somebody is trying to read; and cutting at a space rather than mid-word keeps the
  // first clause of it legible.
  val cut = tidy.take(LONGEST)
  val lastSpace = cut.lastIndexOf(' ')
  return (if (lastSpace > LONGEST / 2) cut.take(lastSpace) else cut) + "…"
}

/** About as much as fits on an error card without pushing the conversation off the screen. */
private const val LONGEST = 180
