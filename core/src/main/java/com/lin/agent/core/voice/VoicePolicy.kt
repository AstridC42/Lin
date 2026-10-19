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

/**
 * Whether the microphone opens again once Lin has finished answering.
 *
 * This is what makes it a conversation rather than a series of questions: ask out loud, hear
 * the answer, reply - without reaching for the phone between each one.
 *
 * Three conditions, and each rules out a way of getting it wrong. Only when it was turned on,
 * because a microphone that reopens by itself is the thing people are right to be wary of.
 * Only when the question was spoken, so typing one question does not leave the phone
 * listening. And only when Lin actually spoke, because reopening after a silent answer would
 * leave somebody talking to a phone that said nothing and is now recording them.
 *
 * The caller must also wait until the speaking has finished. A microphone opened while Lin is
 * still talking hears Lin.
 */
fun shouldListenAgain(voiceChat: Boolean, asked: Asked, spoke: Boolean): Boolean =
  voiceChat && spoke && asked == Asked.SPOKEN

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

/** When Lin reads a reply out loud. */
enum class SpeakWhen {
  /** Never. The reply is on screen; that is enough.  */
  NEVER,

  /**
   * When the question was spoken, which is the default and the one worth having.
   *
   * Speaking every reply because a switch is on means somebody who typed a question in a
   * quiet room gets answered out loud. This way the conversation's mode follows how the
   * person is actually using it rather than a switch they set last week.
   */
  WHEN_SPOKEN_TO,

  /** Every reply, typed or spoken. For somebody who wants a voice assistant, not a chat. */
  ALWAYS,
}

/** Reads the stored name, defaulting to the one that follows how Lin is being used. */
fun speakWhenNamed(stored: String?): SpeakWhen =
  SpeakWhen.entries.firstOrNull { it.name.equals(stored?.trim(), ignoreCase = true) }
    ?: SpeakWhen.WHEN_SPOKEN_TO

/**
 * Whether a reply is spoken aloud.
 *
 * [inCar] overrides a NEVER, but only for a question that was spoken. An answer that is only
 * on screen is no answer at all to somebody driving, and they asked out loud - so a switch
 * set last week at a desk should not decide that. A typed question in a car is still answered
 * in writing: either the car is stationary, or somebody else is holding the phone, and
 * neither wants the phone talking.
 */
fun shouldSpeak(speak: SpeakWhen, asked: Asked, inCar: Boolean = false): Boolean =
  when (speak) {
    SpeakWhen.ALWAYS -> true
    SpeakWhen.WHEN_SPOKEN_TO -> asked == Asked.SPOKEN
    SpeakWhen.NEVER -> inCar && asked == Asked.SPOKEN
  }

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
