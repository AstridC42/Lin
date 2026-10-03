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

package com.google.ai.edge.gallery.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The widest a column of conversation should get, regardless of the screen behind it.
 *
 * Past roughly this width a line of text stops being comfortable to read: the eye loses its place
 * coming back to the start of the next line.
 *
 * It was 720dp, chosen for readability alone, and that turned out to be wider than an unfolded
 * foldable held in portrait - so on the device this was written for, the cap never engaged and
 * unfolding changed nothing visible. 600dp is the width Android itself treats as the boundary
 * between a phone and a large screen, so the layout now changes exactly where the system decides
 * there is spare width to give back.
 */
private val MAX_CONVERSATION_WIDTH: Dp = 600.dp

/**
 * Below this, the screen is a phone and every point of width is needed.
 *
 * Matches Android's own compact/medium boundary, so "the layout changed" lines up with "the system
 * considers this a large screen".
 */
private val LARGE_SCREEN_MIN_WIDTH: Dp = 600.dp

/**
 * Centres its content and stops it growing past a readable width on a large screen.
 *
 * A phone is narrower than the cap, so nothing changes there. Unfolding a foldable, or running on
 * a tablet or in a desktop window, otherwise stretches every message bubble and the input field
 * the full width of the display - which is not more usable, just harder to read, and leaves the
 * send button a hand's travel away from the text it belongs to.
 *
 * This is deliberately not a two-pane layout. A single conversation is the whole point of the
 * screen; splitting it would mean inventing a second thing to show beside it.
 */
@Composable
fun AdaptiveWidthContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    val width =
      if (maxWidth >= LARGE_SCREEN_MIN_WIDTH) minOf(maxWidth, MAX_CONVERSATION_WIDTH) else maxWidth
    Box(modifier = Modifier.width(width).fillMaxHeight()) { content() }
  }
}

/**
 * Whether the current screen is one Android considers large.
 *
 * Exposed so a screen can offer more on a wide display than a narrow one does, rather than only
 * being centred on it.
 */
@Composable
fun isLargeScreen(): Boolean =
  LocalConfiguration.current.screenWidthDp.dp >= LARGE_SCREEN_MIN_WIDTH
