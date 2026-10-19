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

/**
 * How wide the conversation list is when it sits beside the conversation, in dp, or null for a
 * single pane with the list in a drawer.
 *
 * Two panes from 600 dp, the width where Android's own guidance and every list-and-detail app
 * stop treating a screen as a phone - an unfolded Fold, a tablet, a phone in landscape with room.
 * The list takes about a third, held between a width its titles can be read at and one that
 * does not squeeze the conversation.
 *
 * Half open like a book, the split goes on the hinge instead: anything drawn across a crease
 * that is folded is read around a corner. Flat open, the hinge is invisible and ignored.
 */
fun listPaneWidth(windowWidthDp: Int, posture: Posture, hingeLeftDp: Int? = null): Int? {
  if (windowWidthDp < TWO_PANES_FROM_DP) return null
  if (posture == Posture.BOOK && hingeLeftDp != null && hingeLeftDp in MIN_LIST_DP..(windowWidthDp - MIN_LIST_DP)) {
    return hingeLeftDp
  }
  return (windowWidthDp * 0.34f).toInt().coerceIn(MIN_LIST_DP, MAX_LIST_DP)
}

const val TWO_PANES_FROM_DP = 600
private const val MIN_LIST_DP = 260
private const val MAX_LIST_DP = 380
