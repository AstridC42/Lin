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

package com.google.ai.edge.gallery.tools

import android.content.Context
import com.google.ai.edge.gallery.memory.ConversationArchive
import com.google.ai.edge.gallery.memory.MemoryHit
import com.google.ai.edge.gallery.memory.MemoryVaultRepository
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Reaching the vault from inside a conversation.
 *
 * Warm memory is already in the system prompt, so "what do you know about me" needs no call at
 * all. These tools are for the three things the prompt cannot do: change what is remembered,
 * and reach the two layers that are deliberately *not* in the prompt - notes that have cooled,
 * and the conversations themselves.
 *
 * That split is the whole design. Warm notes cost prompt budget on every single turn, so there
 * are few of them and they are short. Everything else is free until it is asked for. An
 * assistant that can look something up is worth more than one that recites.
 *
 * Saving is not silent, by instruction in the description: a fact stored wrongly is repeated
 * with confidence for as long as it sits there, and the moment it is saved is the only moment
 * the user is positioned to correct it.
 */
class MemoryTool(
  private val context: Context,
  private val vault: MemoryVaultRepository,
  /** Overridable so tests do not depend on the wall clock. */
  private val now: () -> Long = { System.currentTimeMillis() },
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Remember something about the user for future conversations - a preference, a name they " +
        "use, a constraint. Only for things worth keeping, not for this conversation's details. " +
        "Say afterwards what you remembered."
  )
  fun rememberThis(
    @ToolParam(
      description = "The fact, short and in the user's own terms, for example 'calls his mother Ima'."
    )
    fact: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val note =
        vault.addUserNote(text = fact, nowMillis = now())
          ?: return@runBlocking failure("There was nothing in that worth remembering.")
      sendProgress("Remembered", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "remembered" to note.text,
        "hint" to
          "Tell the user what you have remembered, in one line, so they can correct it now if it " +
            "is wrong.",
      )
    }
  }

  @Tool(
    description =
      "Forget something previously remembered about the user. Use when they ask you to forget " +
        "something or correct it."
  )
  fun forgetThis(
    @ToolParam(description = "What to forget, in roughly the words it was remembered in.")
    fact: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      vault.forgetMatching(fact)
        ?: return@runBlocking failure(
          // Nothing is removed when two notes match, which is the right failure: erasing the
          // wrong memory is not something the user can see has happened.
          "Nothing matched \"$fact\" closely enough to be sure which memory to remove. Read back " +
            "what is remembered and ask which one they mean."
        )
      sendProgress("Forgotten", inProgress = false)
      mapOf("status" to "succeeded", "hint" to "Confirm in one line that it is forgotten.")
    }
  }

  @Tool(
    description =
      "Search what was remembered from past conversations, or read one back. Use when the user " +
        "refers to something from before that is not already in front of you."
  )
  fun searchMemory(
    @ToolParam(description = "What to look for, in a few words.") query: String,
    @ToolParam(
      description =
        "Optional conversation_id from an earlier result, to read what was actually said in it."
    )
    conversationId: String = "",
  ): Map<String, Any> {
    if (conversationId.isNotBlank()) return readPastConversation(conversationId)
    return runBlocking(Dispatchers.Default) {
      if (query.isBlank()) return@runBlocking failure("No search terms were given.")
      sendProgress("Searching memory", inProgress = true)
      val hits = vault.search(query)
      if (hits.isEmpty()) {
        return@runBlocking mapOf(
          "status" to "succeeded",
          "results" to emptyList<Any>(),
          "hint" to
            "Nothing in memory matches that. Say so plainly rather than guessing at what it " +
              "might have been.",
        )
      }
      // Finding a cold note is itself evidence it still matters, so being searched for warms it.
      vault.touchNotes(hits.filter { it.kind == MemoryHit.Kind.NOTE }.map { it.id }, now())
      sendProgress("Found ${hits.size} in memory", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "results" to
          hits.map {
            buildMap {
              put("kind", if (it.kind == MemoryHit.Kind.NOTE) "note" else "conversation")
              put("text", it.body)
              put("when", humanDate(it.whenMillis))
              if (it.kind == MemoryHit.Kind.CONVERSATION) {
                put("title", it.title)
                put("conversation_id", it.id)
              }
            }
          },
        "hint" to
          "These are summaries. To read what was actually said in one of them, call searchMemory " +
            "again with its conversation_id.",
      )
    }
  }

  /**
   * Reading a transcript back is a parameter on the search rather than a tool of its own.
   *
   * Every tool offered costs its schema out of the same budget as the conversation, on every
   * single turn - and the fixed overhead of this assistant had already reached the point where a
   * brand new conversation could not start. Two tools where one will do is a cost paid on every
   * turn to save the model one call on the rare turn that needs it.
   */
  private fun readPastConversation(conversationId: String): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val digest =
        vault.digest(conversationId)
          ?: return@runBlocking failure(
            "There is no conversation with that id. Use searchMemory first to find one."
          )
      val transcript =
        ConversationArchive(context).read(digest.archivePath)
          ?: return@runBlocking failure(
            "The summary of \"${digest.title}\" is in memory but the full transcript is not on " +
              "the device any more. What is known is: ${digest.summary}"
          )
      sendProgress("Read \"${digest.title}\"", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "title" to digest.title,
        "when" to humanDate(digest.endedAtMillis),
        // Capped hard: a transcript is charged against the same budget as the conversation
        // happening now, and a tool result that fills the window ends the turn it was meant to
        // help with.
        "transcript" to transcript.take(MAX_TRANSCRIPT_CHARS),
        "truncated" to (transcript.length > MAX_TRANSCRIPT_CHARS),
      )
    }
  }

  private fun humanDate(millis: Long): String =
    if (millis <= 0) "unknown"
    else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

  private suspend fun sendProgress(label: String, inProgress: Boolean) {
    executionContext
      ?.actionChannel
      ?.send(SkillProgressToolAction(label = label, inProgress = inProgress))
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  private companion object {
    const val MAX_TRANSCRIPT_CHARS = 4_000
  }
}
