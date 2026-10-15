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

package com.lin.agent.core.fold

/** How the phone is being held, as far as a layout is concerned. */
enum class Posture {
  /** Open or closed, but flat: one screen, laid out the ordinary way. */
  FLAT,

  /**
   * Half open with the fold across the middle, the half below resting on a table.
   *
   * Samsung calls this Flex mode. It is the one posture that changes what a layout should be
   * rather than how big it is: the screen is two surfaces at an angle, and content spanning
   * the crease is being read around a corner.
   */
  TABLETOP,

  /** Half open with the fold down the middle, held like a book. */
  BOOK,
}

/**
 * Which posture the hinge describes.
 *
 * Only half-opened counts. A fold that is flat is one screen whatever its hinge says, and a
 * layout that split itself because a foldable exists would be wrong on every other device and
 * wrong on this one most of the time.
 */
fun postureOf(halfOpened: Boolean, hingeIsHorizontal: Boolean): Posture =
  when {
    !halfOpened -> Posture.FLAT
    hingeIsHorizontal -> Posture.TABLETOP
    else -> Posture.BOOK
  }

/**
 * Whether to put the conversation above the crease and everything you touch below it.
 *
 * Tabletop only. In book posture the two halves are side by side and splitting top from
 * bottom would cut both pages in half, which is worse than not splitting at all.
 */
fun splitAcrossTheCrease(posture: Posture): Boolean = posture == Posture.TABLETOP
