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
