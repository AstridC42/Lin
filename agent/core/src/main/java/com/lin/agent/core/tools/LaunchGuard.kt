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

package com.lin.agent.core.tools

/**
 * Stops a tool that opens another app from opening it over and over.
 *
 * Written for a loop somebody actually hit. A staged action's result has to say the thing is
 * *not* finished - the calendar is open but unsaved, the alarm is filled in but unconfirmed -
 * because that is true and the model must not claim otherwise. A small model reads "not saved"
 * as "did not work" and calls the tool again. The user cancels, comes back, and it opens again.
 *
 * The loop's own duplicate check does not catch it: that matches on the exact call, and a
 * second attempt usually differs by a word in the title or a minute in the time. This matches
 * on the tool, which is the thing being reopened.
 *
 * Time rather than a count, because the legitimate second use - "actually make it four o'clock"
 * - comes after the user has looked at something, and that is never two seconds later.
 */
class LaunchGuard(private val withinMs: Long = DEFAULT_WITHIN_MS) {

  private val lastLaunch = mutableMapOf<String, Long>()

  /**
   * Whether [key] may open something now, recording it if so.
   *
   * Asking is taking: a caller that asks and then does not launch has spent its turn, which is
   * the safe direction to be wrong in.
   */
  @Synchronized
  fun allow(key: String, now: Long): Boolean {
    val last = lastLaunch[key]
    if (last != null && now - last < withinMs && now >= last) return false
    lastLaunch[key] = now
    return true
  }

  /** For when the user has plainly asked again - a new turn they typed themselves. */
  @Synchronized
  fun forget(key: String) {
    lastLaunch.remove(key)
  }

  private companion object {
    /**
     * Long enough to cover a model's retry inside one turn, short enough not to block a person.
     *
     * A retry arrives in seconds. Somebody who has looked at the calendar, cancelled, and asked
     * for a different time takes longer than this to do it.
     */
    const val DEFAULT_WITHIN_MS = 20_000L
  }
}
