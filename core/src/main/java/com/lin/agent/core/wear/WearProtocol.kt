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

package com.lin.agent.core.wear

import org.json.JSONObject

/**
 * What Lin's watch app and the phone say to each other, over Wear OS's Data Layer.
 *
 * The watch cannot run Lin's models, so it is a remote: it sends the question, the phone answers
 * with the same Lin that runs in the app, and the answer comes back. Shared by both apps so the
 * two ends cannot disagree about a path or a field.
 */
object WearProtocol {
  /** Watch to phone: a question, as JSON - see [question]. */
  const val PATH_ASK = "/lin/ask"

  /** Phone to watch: the answer - see [answer]. */
  const val PATH_ANSWER = "/lin/answer"

  /** Phone to watch: the question arrived and Lin is working on it. */
  const val PATH_WORKING = "/lin/working"

  /**
   * The capability the phone app advertises, which is how the watch finds the right phone - the
   * one with Lin on it - among everything it is connected to.
   */
  const val PHONE_CAPABILITY = "lin_phone"

  /** Questions longer than this are cut: a watch keyboard does not produce essays. */
  const val LONGEST_QUESTION = 2_000

  data class Question(val id: String, val text: String)

  data class Answer(val id: String, val text: String, val ok: Boolean = true)

  fun question(q: Question): ByteArray =
    JSONObject().put("id", q.id).put("text", q.text.take(LONGEST_QUESTION)).toString().toByteArray()

  fun questionFrom(bytes: ByteArray): Question? =
    runCatching {
        val json = JSONObject(String(bytes))
        val text = json.optString("text").trim()
        if (text.isEmpty()) null else Question(json.optString("id"), text.take(LONGEST_QUESTION))
      }
      .getOrNull()

  fun answer(a: Answer): ByteArray =
    JSONObject().put("id", a.id).put("text", a.text).put("ok", a.ok).toString().toByteArray()

  fun answerFrom(bytes: ByteArray): Answer? =
    runCatching {
        val json = JSONObject(String(bytes))
        Answer(json.optString("id"), json.optString("text"), json.optBoolean("ok", true))
      }
      .getOrNull()

  /**
   * What the watch is told on top of everything else: the answer is read on a small round
   * screen, or heard from a wrist.
   */
  const val PROMPT =
    "The person is asking from their smartwatch. Your answer is shown on a small round screen " +
      "and may be read aloud, so answer in one to three short sentences: no lists, tables, " +
      "links, headings or markdown. "

  /** Conversations from the watch carry this prefix, so the phone's history shows them too. */
  const val CHAT_PREFIX = "watch-"
}
