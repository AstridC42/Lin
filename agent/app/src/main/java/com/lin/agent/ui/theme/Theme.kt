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

package com.lin.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Lin's own colours.
 *
 * Dynamic colour is gone, and that is the decision rather than an omission. Taking the palette
 * from the wallpaper is a good default for an app with no look of its own; this one now has
 * one, chosen on purpose, and a wallpaper that turns the whole app mint green undoes it. The
 * cost is that Lin no longer matches the rest of the phone, which is the trade: it looks like
 * itself instead.
 *
 * Built from the dark side first, because that is where this app is read - at night, in a car,
 * on a phone that is dark the rest of the time. The light scheme is the same hues brought up,
 * not a separate design.
 *
 * The one rule the colours have to keep: a state that matters is never signalled by hue alone.
 * "Playing" and "not sent yet" differ in lightness as well as colour, so they are still
 * different to somebody who cannot tell blue from amber.
 */
private val Periwinkle = Color(0xFFAFC0FF)
private val PeriwinkleInk = Color(0xFF101218)
private val DeepBlue = Color(0xFF35407A)
private val Ground = Color(0xFF101218)
private val Raised = Color(0xFF1A1D25)
private val RaisedHigh = Color(0xFF262A35)
private val Ink = Color(0xFFF2F3F6)
private val Muted = Color(0xFF8C93A4)
private val Amber = Color(0xFFF0C05A)
private val AmberInk = Color(0xFF231B05)
private val Rose = Color(0xFFFFB3B3)
private val RoseInk = Color(0xFF3A1414)

private val LinDark =
  darkColorScheme(
    primary = Periwinkle,
    onPrimary = PeriwinkleInk,
    primaryContainer = DeepBlue,
    onPrimaryContainer = Ink,
    secondary = Amber,
    onSecondary = AmberInk,
    // Where a staged action lives: written, not sent. Amber on purpose - it is neither a
    // success nor a failure, and the two-colour palettes that only have those make it one.
    secondaryContainer = Color(0xFF2A2415),
    onSecondaryContainer = Color(0xFFF7E6B8),
    tertiary = Color(0xFF8ED6AD),
    onTertiary = Color(0xFF07281A),
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceContainer = Raised,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = RaisedHigh,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    outline = Color(0xFF3A3F4C),
    outlineVariant = Color(0xFF262A35),
    error = Rose,
    onError = RoseInk,
    errorContainer = Color(0xFF3A1A1A),
    onErrorContainer = Color(0xFFFFD6D6),
  )

private val LinLight =
  lightColorScheme(
    primary = Color(0xFF3F4FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF101218),
    secondary = Color(0xFF7A5C00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE9A8),
    onSecondaryContainer = Color(0xFF2A2415),
    tertiary = Color(0xFF1F6B47),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFBFBFE),
    onSurface = Color(0xFF14161C),
    surfaceContainer = Color(0xFFF0F1F7),
    surfaceContainerHigh = Color(0xFFEAECF4),
    surfaceContainerHighest = Color(0xFFE3E6F0),
    surfaceVariant = Color(0xFFF0F1F7),
    onSurfaceVariant = Color(0xFF5A6072),
    outline = Color(0xFFBDC2D2),
    outlineVariant = Color(0xFFDDE0EA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
  )

@Composable
fun LinTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (darkTheme) LinDark else LinLight, content = content)
}
