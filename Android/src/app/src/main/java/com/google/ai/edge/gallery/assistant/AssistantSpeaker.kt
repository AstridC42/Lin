/*
 * Copyright 2026 The Star Storyteller Authors
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

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private const val TAG = "AGAssistantSpeaker"

/**
 * Reads assistant replies out loud with the platform text-to-speech engine.
 *
 * Hands-free answers are most of what makes a phone assistant useful, and the on-device models
 * here produce markdown, so the text is stripped of formatting before it is spoken - otherwise the
 * engine reads asterisks and backticks aloud.
 */
class AssistantSpeaker(context: Context) {
  private var engine: TextToSpeech? = null
  private var ready = false

  // Progress callbacks arrive on a binder thread; everything waiting on them is UI state.
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())

  init {
    engine =
      TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
          engine?.language = Locale.getDefault()
          engine?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) {}

              override fun onDone(utteranceId: String?) {
                handler.post { finish() }
              }

              @Deprecated("Required by the base class")
              override fun onError(utteranceId: String?) {
                handler.post { finish() }
              }

              override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { finish() }
              }

              // A flushed utterance never reports done, so a caller waiting on the one it just
              // replaced would wait forever.
              override fun onStop(utteranceId: String?, interrupted: Boolean) {
                handler.post { finish() }
              }
            }
          )
        } else {
          Log.w(TAG, "Text-to-speech is unavailable on this device (status $status)")
        }
      }
  }

  /** Called when the current utterance finishes, is cancelled, or fails. Cleared after it runs. */
  private var onFinished: (() -> Unit)? = null

  /**
   * Speaks [text], replacing anything currently being spoken.
   *
   * [onDone] runs when speaking ends, however it ends. A hands-free conversation depends on that
   * being reliable: the microphone reopens when the assistant stops talking, and an utterance that
   * failed silently and never reported back would leave the loop waiting forever with no way to
   * carry on except tapping the screen - which is the one thing hands-free means you cannot do.
   * It also must not fire early, or the microphone opens while the phone is still speaking and
   * records the assistant answering itself.
   */
  fun speak(text: String, onDone: (() -> Unit)? = null) {
    val spoken = toSpeakableText(text)
    val currentEngine = engine
    if (spoken.isEmpty() || currentEngine == null || !ready) {
      if (!ready) Log.d(TAG, "Text-to-speech is not ready yet; dropping this reply")
      // Report completion anyway: from the caller's point of view the speaking is over, and a
      // caller waiting on it has to be released whether or not a word was said.
      onDone?.invoke()
      return
    }
    onFinished = onDone
    val result = currentEngine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    if (result != TextToSpeech.SUCCESS) {
      Log.w(TAG, "Text-to-speech refused the utterance (result $result)")
      finish()
    }
  }

  /** Runs the pending completion callback exactly once. */
  private fun finish() {
    val callback = onFinished
    onFinished = null
    callback?.invoke()
  }

  fun stop() {
    engine?.stop()
    finish()
  }

  fun release() {
    onFinished = null
    engine?.stop()
    engine?.shutdown()
    engine = null
    ready = false
  }

  companion object {
    private const val UTTERANCE_ID = "star-storyteller-reply"
    private const val MAX_SPOKEN_CHARS = 4000

    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val EMPHASIS = Regex("(\\*{1,3}|_{1,3})(\\S(?:.*?\\S)?)\\1")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE)
    private val BULLET = Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE)
    private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
    private val WHITESPACE = Regex("\\s+")

    /** Flattens markdown into something a speech engine can read naturally. */
    fun toSpeakableText(markdown: String): String =
      markdown
        .replace(CODE_BLOCK, " ")
        .replace(INLINE_CODE, "$1")
        .replace(LINK, "$1")
        .replace(EMPHASIS, "$2")
        .replace(HEADING, "")
        .replace(BULLET, "")
        .replace(BLOCKQUOTE, "")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_SPOKEN_CHARS)
  }
}

/** Remembers an [AssistantSpeaker] and shuts its engine down when the screen leaves composition. */
@Composable
fun rememberAssistantSpeaker(): AssistantSpeaker {
  val context = LocalContext.current
  val speaker = remember { AssistantSpeaker(context) }
  DisposableEffect(speaker) { onDispose { speaker.release() } }
  return speaker
}
