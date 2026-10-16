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

package com.lin.agent.core.car

/**
 * When Lin may ring somebody without the user pressing the call button.
 *
 * Three states rather than a switch, because the honest answer differs by where you are. At a
 * desk, staging the call in the dialer costs one tap and protects against a misheard name. At
 * 70mph that same tap is the thing nobody should be doing, and an assistant that makes you
 * reach for the phone to finish a call is worse than no assistant.
 */
enum class DialPolicy {
  /** Always stage it in the dialer. The safe default, and where everyone starts. */
  NEVER,

  /** Dial straight out while the phone is in the car, stage it everywhere else. */
  IN_THE_CAR,

  /** Dial straight out, wherever you are. */
  ALWAYS,
}

/** Reads the stored name, defaulting to the careful one for anything unrecognised. */
fun dialPolicyNamed(stored: String?): DialPolicy =
  DialPolicy.entries.firstOrNull { it.name.equals(stored?.trim(), ignoreCase = true) }
    ?: DialPolicy.NEVER

/**
 * Whether this call goes out now or waits in the dialer.
 *
 * Two conditions and no third: the policy, and where the phone is. The permission is checked
 * by the caller - this says what the user asked for, not what Android allows, and mixing the
 * two is how a refused permission turns into a call nobody meant to place.
 */
fun mayDialDirectly(policy: DialPolicy, inCar: Boolean): Boolean =
  when (policy) {
    DialPolicy.NEVER -> false
    DialPolicy.IN_THE_CAR -> inCar
    DialPolicy.ALWAYS -> true
  }

/** One line of information on Lin's car screen: a label, and the thing worth knowing. */
data class CarLine(val title: String, val detail: String = "")

/**
 * What is worth knowing in a car, in the order it is worth knowing it.
 *
 * Declared here rather than decided where the screen is built, because the order *is* the
 * feature. A car screen is read in the half second somebody is not looking at the road, and
 * the host decides how many rows fit - so what gets dropped when there is room for two is not
 * a layout detail, it is which two things Lin tells a driver.
 */
enum class CarInfo {
  /** Something is wrong that makes everything else pointless - no model, no permission. */
  TROUBLE,

  /** Whether Lin is listening, which is the one thing the button's state cannot show. */
  LISTENING,

  /** The next reminder, which is the only line with a deadline attached. */
  REMINDER,

  /** Who is waiting for a reply. */
  MESSAGES,

  /** What is playing, last because it is the one thing already audible. */
  PLAYING,
}

/**
 * The rows for the car screen: the most important ones that fit, and nothing else.
 *
 * [limit] is the host's own content limit, not a guess. Zero or fewer is a real answer rather
 * than an error - it means the screen has room for the microphone and nothing more, which is
 * exactly the collapsed state - so this returns no rows rather than one squeezed in. The
 * guard is written to catch zero as well as a negative, which is belt and braces: `take(0)`
 * would also give nothing, but `take(-1)` throws, and a host reporting a negative limit is
 * not a reason for a driver's screen to crash.
 *
 * A line with no title is dropped, by the same rule the persona prompt follows: a row reading
 * "Next reminder -" tells a driver nothing and costs them a glance.
 */
fun carPane(lines: Map<CarInfo, CarLine>, limit: Int): List<CarLine> {
  if (limit <= 0) return emptyList()
  return CarInfo.entries
    .mapNotNull { lines[it] }
    .filter { it.title.isNotBlank() }
    .take(limit)
}
