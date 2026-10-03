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

package com.google.ai.edge.gallery.agent

/**
 * Saying what went wrong in words the person holding the phone can act on.
 *
 * The engine's own messages are written for whoever is debugging the engine. Two of them have now
 * reached a user, and both arrived as a wall of internals in the middle of a conversation:
 *
 *     Error: Status Code: 3. Message: Input token ids are too long. Exceeding the maximum
 *     number of tokens allowed: 4230 >= 4000
 *
 *     Error: Status Code: 3. Message: Failed to parse tool calls from response: ... with error:
 *     FallThrough(ParseCancelledError(NoAltError(NoViableAltError { base: BaseRecognitionError
 *     { message: "", offending_token: GenericToken { token_type: 13, channel: 0, ...
 *
 * There is nothing in the second that tells anybody what to do, and the first reads as a crash
 * when it is a setting. Neither is a reason to hide what happened - the detail is kept, because
 * it is the only account of the failure on a phone with no computer attached - but it goes after
 * a sentence saying what it means.
 *
 * Deliberately matched on substrings rather than status codes. The code is 3 for both of these
 * and for much else; the text is what distinguishes them, and when the engine changes its wording
 * the match simply stops firing and the raw message comes through as before.
 */
object EngineErrors {

  /** [raw] with an explanation in front of it, or unchanged when there is nothing to add. */
  fun explain(raw: String): String {
    val explanation = explanationFor(raw) ?: return raw
    return "$explanation\n\n$raw"
  }

  private fun explanationFor(raw: String): String? {
    val lower = raw.lowercase()
    return when {
      // The conversation plus everything standing in front of it no longer fits.
      lower.contains("token ids are too long") || lower.contains("maximum number of tokens") ->
        "This conversation no longer fits in the model's context. Start a new chat, or raise " +
          "Max tokens with the sliders at the top of this screen."

      // The model wrote something that was meant to be a tool call and was not one.
      lower.contains("failed to parse tool calls") || lower.contains("parse fc tool calls") ->
        "The model tried to use a tool and wrote the request wrongly, so nothing ran. Asking " +
          "again often works; a larger model gets this right more often."

      // No engine behind the conversation, which is a lifecycle problem rather than a model one.
      lower.contains("not initialized") || lower.contains("model not initialized") ->
        "The model is not loaded. Wait for it to finish loading, or pick it again from the chip " +
          "at the top."

      lower.contains("out of memory") || lower.contains("oom") ->
        "The phone ran out of memory. Close other apps, or choose a smaller model."

      else -> null
    }
  }
}
