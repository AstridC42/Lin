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
 * Whether Ollama turned a turn away because the model cannot be asked to think.
 *
 * Ollama accepts `think` only for models whose template declares it, and answers anything else
 * with 400 and `"… does not support thinking"`. A GGUF pulled from Hugging Face can reason
 * perfectly well and still not declare it - the reasoning just arrives inline in the reply,
 * which is how OpenWebUI shows thinking for exactly the model that refused Lin. So this is not
 * a failure to report but a request to make again without the field.
 */
fun thinkingRefused(status: Int, body: String): Boolean =
  status == 400 && body.contains("does not support thinking", ignoreCase = true)

/** What one piece of a streamed reply turned out to be: thinking, answer, or some of each. */
data class Split(val thought: String = "", val text: String = "")

/**
 * Reasoning a model writes into its own reply, taken back out of it.
 *
 * A model that thinks without the server knowing writes `<think>…</think>` at the start of its
 * answer. Left alone, that is the first thing on the screen and the first thing spoken aloud -
 * a page of the model talking to itself before it says hello. Taken out, it goes where the
 * server's own thinking goes.
 *
 * Streamed, which is the whole difficulty: a tag can arrive split across chunks - "<thi" then
 * "nk>" - and a splitter that only looked for whole tags would show the half that came first.
 * So text that could be the start of a tag is held back until the next chunk says whether it
 * was one.
 */
class InlineThinking(private val open: String = "<think>", private val close: String = "</think>") {

  private var inside = false
  private var justClosed = false
  private val pending = StringBuilder()

  /** One chunk in; whatever is now certain to be thinking or answer, out. */
  fun feed(chunk: String): Split {
    pending.append(chunk)
    val thought = StringBuilder()
    val text = StringBuilder()
    while (true) {
      val tag = if (inside) close else open
      val at = pending.indexOf(tag)
      if (at >= 0) {
        emit(pending.substring(0, at), thought, text)
        pending.delete(0, at + tag.length)
        inside = !inside
        justClosed = !inside
        continue
      }
      // Keep back the longest ending that could still become the tag.
      val keep = (minOf(tag.length - 1, pending.length) downTo 1).firstOrNull { tag.startsWith(pending.takeLast(it)) } ?: 0
      emit(pending.substring(0, pending.length - keep), thought, text)
      pending.delete(0, pending.length - keep)
      break
    }
    return Split(thought.toString(), text.toString())
  }

  /** The end of the reply: anything held back was never a tag after all. */
  fun finish(): Split {
    val thought = StringBuilder()
    val text = StringBuilder()
    emit(pending.toString(), thought, text)
    pending.clear()
    return Split(thought.toString(), text.toString())
  }

  private fun emit(part: String, thought: StringBuilder, text: StringBuilder) {
    if (part.isEmpty()) return
    if (inside) {
      thought.append(part)
      return
    }
    // The blank line a model leaves after its thinking is not the start of its answer.
    val said = if (justClosed) part.trimStart() else part
    if (said.isEmpty()) return
    justClosed = false
    text.append(said)
  }
}
