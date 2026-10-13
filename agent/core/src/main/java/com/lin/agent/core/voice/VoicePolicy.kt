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

package com.lin.agent.core.voice

/** How the app was opened. */
enum class Launch {
  /** From the launcher, or by coming back to it. */
  NORMAL,

  /**
   * Summoned as the device's assistant - the power button, or the gesture.
   *
   * Different from a normal launch in one way that matters: somebody holding the power button
   * is not holding a keyboard. They expect to speak.
   */
  ASSIST,
}

/** How the current turn's question arrived. */
enum class Asked {
  TYPED,
  SPOKEN,
}

/**
 * Whether Lin starts listening the moment it opens.
 *
 * Only when summoned as the assistant, and only with a microphone to listen with. Opening the
 * app from the launcher and having it start recording would be a surprise of exactly the kind
 * an always-on assistant has to avoid - the microphone opens because somebody asked for it, not
 * because the app came to the front.
 */
fun shouldListenOnLaunch(launch: Launch, microphoneGranted: Boolean): Boolean =
  launch == Launch.ASSIST && microphoneGranted

/**
 * Whether a reply is spoken aloud.
 *
 * Two conditions, and the second is the one worth having. Speaking every reply because the
 * setting is on means somebody who typed a question in a quiet room gets answered out loud -
 * so Lin speaks when it was spoken to, and otherwise writes. The conversation's mode follows
 * how the person is actually using it rather than a switch they set last week.
 */
fun shouldSpeak(spokenRepliesOn: Boolean, asked: Asked): Boolean =
  spokenRepliesOn && asked == Asked.SPOKEN

/**
 * What to read out of a reply.
 *
 * Markdown is written to be looked at. Read aloud, `**bold**` becomes "star star bold star
 * star" and a numbered list of options becomes an unbroken sentence - so the decoration comes
 * off and the structure becomes pauses. This is deliberately not a Markdown parser: it strips
 * what these models actually emit, and anything it misses is read as written rather than
 * mangled.
 */
fun forSpeaking(reply: String): String =
  reply
    .lineSequence()
    .map { line ->
      line
        .trim()
        // Headings, list bullets and numbering: the shape is what the pause conveys, and the
        // characters themselves are noise.
        .removePrefix("#").removePrefix("#").removePrefix("#").trim()
        .removePrefix("- ")
        .removePrefix("* ")
        .replace(NUMBERED, "")
        .replace(EMPHASIS, "")
        .replace(CODE_TICKS, "")
        .trim()
    }
    .filter { it.isNotEmpty() }
    // A full stop between lines, so a list is read as separate items rather than run
    // together - but only where the line does not already end in something that makes a
    // speech engine pause. "Options:." is how a list introduction gets read as a sentence.
    .joinToString(" ") { if (it.isNotEmpty() && it.last() in ENDINGS) it else "$it." }
    .trim()

private val NUMBERED = Regex("^\\d+[.)]\\s+")
/** Terminal punctuation a speech engine already pauses on. */
private const val ENDINGS = ".?!:;,"

/**
 * Markdown emphasis, and deliberately not every underscore.
 *
 * An underscore between two alphanumerics is part of a word - `OLLAMA_HOST`, a filename, an
 * identifier - and stripping it turns the one thing in a reply the user most needs read
 * correctly into something else. Only underscores at a word edge are emphasis.
 */
private val EMPHASIS = Regex("\\*{1,2}|(?<![A-Za-z0-9])_{1,2}|_{1,2}(?![A-Za-z0-9])")
private val CODE_TICKS = Regex("`+")
