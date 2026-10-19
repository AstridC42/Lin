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

package com.lin.agent.core.media

/** One app that is playing something, or could be. */
data class Session(
  val app: String,
  val title: String = "",
  val artist: String = "",
  val playing: Boolean = false,
  /** Ordering only: a bigger number is more recent. */
  val activeAt: Long = 0,
  /** What this session says it can actually do. */
  val can: Set<MediaCommand> = emptySet(),
)

/** What can be asked of whatever is playing. */
enum class MediaCommand {
  PLAY,
  PAUSE,
  NEXT,
  PREVIOUS,
}

/**
 * The session a command means, out of everything open.
 *
 * This is the whole difficulty of media control on a phone. Three apps hold a media session at
 * once on an ordinary day - a music app, a video in a browser tab, a podcast paused last
 * Tuesday - and "pause" meaning the wrong one is the difference between useful and infuriating.
 *
 * Something playing wins, because that is what "pause" is about. Among several, the most
 * recently active. With nothing playing, the most recently active again, so "play" resumes what
 * was last listened to rather than reviving a podcast from last week.
 */
fun chooseSession(sessions: List<Session>): Session? {
  if (sessions.isEmpty()) return null
  val playing = sessions.filter { it.playing }
  val pool = playing.ifEmpty { sessions }
  return pool.maxByOrNull { it.activeAt }
}

/**
 * What is playing, for the model to read.
 *
 * Names the app as well as the track. "Something is playing" is useless to somebody asking
 * what it is, and the app is often the part they wanted.
 */
fun describeNowPlaying(sessions: List<Session>): String {
  val chosen = chooseSession(sessions) ?: return "Nothing is playing."
  val what =
    listOf(chosen.title, chosen.artist).filter { it.isNotBlank() }.joinToString(" - ")
  val state = if (chosen.playing) "Playing" else "Paused"
  return if (what.isEmpty()) "$state in ${chosen.app}, with nothing named."
  else "$state in ${chosen.app}: $what"
}

/**
 * Whether [command] can be asked of [session].
 *
 * Checked rather than attempted, because a transport control that goes nowhere is silent: the
 * model says "skipped" and the same song keeps playing. Saying it cannot is the honest answer
 * and the one a person can act on.
 */
fun canDo(session: Session, command: MediaCommand): Boolean = command in session.can

/**
 * How far one step moves the volume, and the ceiling a step may reach.
 *
 * Relative, never absolute, and never to the top. Volume is the one media control that can
 * actually hurt somebody - headphones at full on a misheard "turn it up" is an injury rather
 * than an annoyance - so a step is a step, and a run of them stops short of maximum. Getting
 * all the way up stays possible by hand, where the person can hear what they are doing.
 */
fun volumeAfterStep(current: Int, max: Int, up: Boolean): Int {
  if (max <= 0) return current
  val step = (max / STEPS).coerceAtLeast(1)
  val ceiling = (max * CEILING_NUMERATOR) / CEILING_DENOMINATOR
  return if (up) {
    // Already above the ceiling by hand: leave it rather than pulling it down, which would be
    // a surprise of its own.
    if (current >= ceiling) current else (current + step).coerceAtMost(ceiling)
  } else {
    (current - step).coerceAtLeast(0)
  }
}

/** Roughly a tenth of the range per step, which is what a volume key does. */
private const val STEPS = 10

private const val CEILING_NUMERATOR = 4

private const val CEILING_DENOMINATOR = 5
