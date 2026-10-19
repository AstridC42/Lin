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

package com.lin.agent.core.training

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One turn of a conversation, as the training corpus records it.
 *
 * A type of its own rather than the engine's `Message`, so this module stays independent of the
 * engine and so the recorded format cannot drift every time that class gains a field. The app
 * maps between them, which is three lines and the right place for the coupling to live.
 */
data class TrainingTurn(val role: Role, val text: String, val toolName: String = "") {
  enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
  }
}

/**
 * Turning conversations into something a trainer will accept.
 *
 * The output is JSONL with a `messages` key - one conversation per line, each a list of
 * role/content turns. That is the conversational format TRL's SFT trainer reads directly,
 * applying the model's own chat template; ShareGPT-style data has to be converted into it
 * anyway, so starting here saves a step. Getting this shape wrong would make the whole corpus
 * useless, which is why it is pinned by tests rather than assumed.
 */
object TrainingData {

  /** Conversations per file, as asked for. */
  const val PACK_SIZE = 30

  /**
   * How long a gap makes the next message a new conversation.
   *
   * There has to be a boundary somewhere, because nothing in this app ends a conversation -
   * the thread simply continues. Thirty minutes is both how people think about it and, as it
   * happens, the thing that keeps the thread from growing past the model's context forever.
   */
  const val NEW_CONVERSATION_GAP_MS = 30 * 60 * 1000L

  /** Whether a message arriving now belongs to a new conversation rather than the last one. */
  fun startsNewConversation(lastActiveAt: Long, now: Long): Boolean =
    lastActiveAt <= 0L || now - lastActiveAt >= NEW_CONVERSATION_GAP_MS

  /**
   * [turns] with everything unusable removed, or empty if nothing is left worth keeping.
   *
   * Three things go, and each would actively damage a fine-tune rather than merely bulk it out:
   *
   *  - blank turns, which teach the model to say nothing;
   *  - a trailing question with no answer, which is what a failed turn leaves behind. Train on
   *    that and the next thing in the file becomes the answer to it;
   *  - system turns, which carry the prompt rather than the conversation. The trainer applies
   *    its own template, and a prompt repeated across every example is a prompt being learnt as
   *    content.
   *
   * And a conversation needs both halves: somebody asking and something answering. A stretch of
   * one-sided text is not an example of anything.
   */
  fun usable(turns: List<TrainingTurn>): List<TrainingTurn> {
    val kept =
      turns
        .filter { it.role != TrainingTurn.Role.SYSTEM }
        .filter { it.text.isNotBlank() }
        .toMutableList()

    // A trailing turn that is not the assistant's is an unanswered one: the question whose reply
    // failed, or a tool result the model never got to use.
    while (kept.isNotEmpty() && kept.last().role != TrainingTurn.Role.ASSISTANT) {
      kept.removeAt(kept.size - 1)
    }

    val hasAsk = kept.any { it.role == TrainingTurn.Role.USER }
    val hasAnswer = kept.any { it.role == TrainingTurn.Role.ASSISTANT }
    return if (hasAsk && hasAnswer) kept else emptyList()
  }

  /**
   * One JSONL line for [turns], or null if there is nothing worth training on.
   *
   * No newline: the caller joins lines, because a file is a sequence of these and the last one
   * should not be special.
   */
  fun asJsonl(turns: List<TrainingTurn>): String? {
    val kept = usable(turns)
    if (kept.isEmpty()) return null

    val messages = JSONArray()
    for (turn in kept) {
      messages.put(
        JSONObject().put("role", wireRole(turn.role)).put("content", turn.text).apply {
          // The tool's name, where a tool turn has one. Tool-use data is worth more than plain
          // chat for this app's purposes, and a result with no name says nothing about which
          // call produced it.
          if (turn.role == TrainingTurn.Role.TOOL && turn.toolName.isNotEmpty()) {
            put("name", turn.toolName)
          }
        }
      )
    }
    return JSONObject().put("messages", messages).toString()
  }

  /** The role names a trainer expects. Lower case, and `tool` for a tool result. */
  private fun wireRole(role: TrainingTurn.Role): String =
    when (role) {
      TrainingTurn.Role.USER -> "user"
      TrainingTurn.Role.ASSISTANT -> "assistant"
      TrainingTurn.Role.SYSTEM -> "system"
      TrainingTurn.Role.TOOL -> "tool"
    }

  /**
   * What to call pack number [sequence], finished at [at].
   *
   * Zero-padded so the files sort in the order they were made in any file manager, and dated so
   * a directory of them reads as a history rather than a pile. `.jsonl` because that is what it
   * is, and because a trainer's loader keys off it.
   */
  fun packName(sequence: Int, at: Long): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
    return "lin-%04d-%s.jsonl".format(sequence, day)
  }

  /** The sequence number in a pack's name, or null if that is not a pack name. */
  fun sequenceOf(name: String): Int? =
    Regex("^lin-(\\d{4})-\\d{4}-\\d{2}-\\d{2}\\.jsonl$").find(name)?.groupValues?.get(1)?.toIntOrNull()
}
