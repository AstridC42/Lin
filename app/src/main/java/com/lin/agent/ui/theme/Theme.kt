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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lin.agent.R

/*
 * Lin's look, from the Astrid-design system: the logo's near-black navy with warm bone ink,
 * orange for the one primary action, and copper, brass and arcane purple as accents. Dark is
 * the default; light swaps the ground to bone. Values are the system's tokens, named here as
 * they are there, so a change to the system can be carried over line by line.
 *
 * The rule the colours keep: a state that matters is never signalled by hue alone.
 */

// Dark theme tokens.
private val Surface = Color(0xFF0F1721)
private val SurfaceRaised = Color(0xFF18222F)
private val SurfaceSunken = Color(0xFF0A1119)
private val Line = Color(0xFF2C3A4D)
private val LineStrong = Color(0xFF6B7A90)
private val Ink = Color(0xFFF8F6E8)
private val InkMuted = Color(0xFFAAB2BD)
// orange-text: the dark theme's orange, bright enough to read as text as well as fill.
private val Orange = Color(0xFFF2701F)
private val OnOrange = Color(0xFF0F1721)
private val Brass = Color(0xFFC79848)
private val ArcaneText = Color(0xFFB08CF0)
private val Danger = Color(0xFFFF7A70)

// The deep grounds, the same in both themes.
private val SilentBlue = Color(0xFF0D365D)
private val PurpleOfTheDeep = Color(0xFF352641)
private val BloodRed = Color(0xFF5F1313)

private val LinDark =
  darkColorScheme(
    primary = Orange,
    onPrimary = OnOrange,
    // The person's own bubble: navy, the pair to orange in Marlin's hair.
    primaryContainer = SilentBlue,
    onPrimaryContainer = Ink,
    secondary = Brass,
    onSecondary = OnOrange,
    // Where a staged action lives: written, not sent.
    secondaryContainer = PurpleOfTheDeep,
    onSecondaryContainer = Ink,
    tertiary = ArcaneText,
    onTertiary = OnOrange,
    background = Surface,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceContainerLowest = SurfaceSunken,
    surfaceContainerLow = SurfaceRaised,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = Color(0xFF223042),
    surfaceContainerHighest = Line,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = InkMuted,
    outline = LineStrong,
    outlineVariant = Line,
    error = Danger,
    onError = OnOrange,
    errorContainer = BloodRed,
    onErrorContainer = Ink,
  )

private val LinLight =
  lightColorScheme(
    // The light theme's orange-text: Material uses primary for text and labels as well as
    // fills, and plain orange is too faint as text on bone.
    primary = Color(0xFFA84300),
    onPrimary = Color(0xFFF8F6E8),
    primaryContainer = SilentBlue,
    onPrimaryContainer = Color(0xFFF8F6E8),
    secondary = Color(0xFF7A5A10),
    onSecondary = Color(0xFFF8F6E8),
    secondaryContainer = Color(0xFFEFE2C4),
    onSecondaryContainer = Color(0xFF0F1721),
    tertiary = Color(0xFF5E3D94),
    onTertiary = Color(0xFFF8F6E8),
    background = Color(0xFFF8F6E8),
    onBackground = Color(0xFF0F1721),
    surface = Color(0xFFF8F6E8),
    onSurface = Color(0xFF0F1721),
    surfaceContainerLowest = Color(0xFFEFEADB),
    surfaceContainerLow = Color(0xFFFDFDFD),
    surfaceContainer = Color(0xFFFDFDFD),
    surfaceContainerHigh = Color(0xFFEFEADB),
    surfaceContainerHighest = Color(0xFFE4DECB),
    surfaceVariant = Color(0xFFFDFDFD),
    onSurfaceVariant = Color(0xFF4A5361),
    outline = Color(0xFF6F6A58),
    outlineVariant = Color(0xFFD6D0BD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFDFDFD),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
  )

/** IBM Plex Mono, in its Nerd Font build: everything that is read. */
private val Text =
  FontFamily(
    Font(R.font.blex_mono_regular, FontWeight.Normal),
    Font(R.font.blex_mono_medium, FontWeight.Medium),
    Font(R.font.blex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.blex_mono_bold, FontWeight.Bold),
  )

/** BigBlue Terminal, a 1980s PC pixel face, by VileR: titles only, at multiples of 16. */
private val Display = FontFamily(Font(R.font.big_blue_terminal, FontWeight.Normal))

private fun display(size: Int, line: Int, spacing: Float = 0f) =
  TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.em)

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
  TextStyle(fontFamily = Text, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.em)

private val LinType =
  Typography(
    displayLarge = display(64, 64),
    displayMedium = display(48, 48),
    displaySmall = display(32, 40),
    headlineLarge = display(32, 40),
    headlineMedium = display(32, 40),
    headlineSmall = display(32, 40),
    // The top bar's title: the pixel face, as an overline.
    titleLarge = display(16, 24, 0.08f),
    titleMedium = text(16, 24, FontWeight.SemiBold),
    titleSmall = text(14, 20, FontWeight.SemiBold),
    bodyLarge = text(15, 24),
    bodyMedium = text(14, 22),
    bodySmall = text(13, 20),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(12, 16, FontWeight.Medium, 0.04f),
    labelSmall = text(11, 16, FontWeight.Medium, 0.04f),
  )

/** radius-sm 3, radius-md 6 (buttons, inputs, bubbles), radius-lg 12 (cards). */
private val LinShapes =
  Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
  )

@Composable
fun LinTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (darkTheme) LinDark else LinLight,
    typography = LinType,
    shapes = LinShapes,
    content = content,
  )
}
