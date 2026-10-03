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

package com.google.ai.edge.gallery.tools

/**
 * Stops the model asking the same question over and over.
 *
 * The tool-calling loop runs inside the LiteRT-LM engine: the model asks for a tool, the engine
 * runs it, hands back the result and lets the model go again. Nothing on the Kotlin side sits in
 * the middle of that, so there is no turn counter to cap and no obvious place to say "enough".
 *
 * Which matters because a small model handed a failure does not reliably change course. It reads
 * the error, decides the call was nearly right, and makes the same call again - and a tool that
 * fails for a structural reason fails identically every time. From outside, the assistant simply
 * stops responding and the phone gets warm.
 *
 * So each tool that can fail this way counts its own repeats. The third identical call inside the
 * window comes back with a different kind of answer: not another error to work around, but an
 * instruction to stop and tell the user. That is the part that matters - a model stuck in a loop
 * needs somewhere to go, and "say what went wrong" is somewhere.
 *
 * Keyed on arguments as well as name, so a model that is genuinely working through a list of
 * different URLs is never interrupted. Only doing the same thing again is.
 */
class ToolCallGuard(
  private val repeatsAllowed: Int = DEFAULT_REPEATS_ALLOWED,
  private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
  private val now: () -> Long = { System.currentTimeMillis() },
) {

  private data class Attempt(val count: Int, val firstAtMillis: Long, val lastError: String)

  private val attempts = mutableMapOf<String, Attempt>()

  /**
   * Records that a call failed.
   *
   * Only failures are counted. A model that legitimately calls the same tool again - reading
   * three pages, searching twice for different things, or simply asked the same question later -
   * must never be cut off, and a call that worked is evidence of exactly that.
   *
   * @param tool the tool's name.
   * @param arguments what it was called with; repeats are only repeats if these match.
   */
  @Synchronized
  fun recordFailure(tool: String, arguments: String, error: String = "") {
    val at = now()
    val key = key(tool, arguments)
    val previous = attempts[key]
    attempts[key] =
      if (previous == null || at - previous.firstAtMillis > windowMillis) {
        // A repeat long after the fact is a new request, not a loop.
        Attempt(count = 1, firstAtMillis = at, lastError = error)
      } else {
        previous.copy(count = previous.count + 1, lastError = error.ifBlank { previous.lastError })
      }
    if (attempts.size > MAX_TRACKED) {
      // Unbounded growth would be its own bug. The oldest are the least likely to be a live loop.
      val cutoff = at - windowMillis
      attempts.entries.removeAll { it.value.firstAtMillis < cutoff }
    }
  }

  /**
   * The answer to give instead of running the call again, or null to go ahead.
   *
   * Checked before the work rather than after, so a loop costs nothing further once it is
   * recognised - and so the user is not waiting on a third timeout to find out.
   */
  @Synchronized
  fun loopBreaker(tool: String, arguments: String): Map<String, Any>? {
    val attempt = attempts[key(tool, arguments)] ?: return null
    if (now() - attempt.firstAtMillis > windowMillis) return null
    if (attempt.count < repeatsAllowed) return null
    return loopBreakerResult(tool, attempt.lastError)
  }

  private fun key(tool: String, arguments: String) = "$tool\u0000$arguments"

  /** Forgets everything, for when a new conversation starts. */
  @Synchronized
  fun reset() {
    attempts.clear()
  }

  /**
   * The answer to give once a call has repeated too often.
   *
   * Deliberately `status: succeeded`. A failure is what the model has already been handed twice
   * and worked around twice; handing it a third tells it to try again. This is the turn ending,
   * so it reads as a result with an instruction in it.
   */
  fun loopBreakerResult(tool: String, lastError: String): Map<String, Any> =
    mapOf(
      "status" to "succeeded",
      "stop" to true,
      "result" to
        "You have now called $tool the same way several times and it has not worked. Stop " +
          "calling it. Tell the user plainly what you were trying to do and that it did not " +
          "work, in one or two sentences. The reason was: $lastError",
    )

  companion object {
    /** Two tries at the same thing is persistence; the third is a loop. */
    const val DEFAULT_REPEATS_ALLOWED = 2

    /**
     * How long a repeat still counts as part of the same loop.
     *
     * Long enough to cover a slow tool on a slow phone, short enough that asking the same thing
     * again later in a conversation is a fresh request rather than a refusal.
     */
    const val DEFAULT_WINDOW_MILLIS = 120_000L

    private const val MAX_TRACKED = 64
  }
}
