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

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.memory.MemoryBook
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Remembering and forgetting things about the user.
 *
 * What is remembered is already in the model's system prompt, so there is no tool for reading it
 * back - asking "what do you know about me" is answered from the prompt without a call. These two
 * exist only to change it.
 *
 * Saving is not silent by design. The tool's description says to say what was remembered, because
 * a fact stored wrongly is repeated confidently for as long as it sits there, and the moment it is
 * saved is the only moment the user is in a position to correct it.
 */
class MemoryTool(
  private val repository: DataStoreRepository,
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
      val updated =
        MemoryBook.remember(repository.readAssistantMemory(), fact, now())
          ?: return@runBlocking failure("There was nothing in that worth remembering.")
      repository.saveAssistantMemory(updated)
      sendProgress("Remembered", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "remembered" to updated.getFact(0).text,
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
      val current = repository.readAssistantMemory()
      val updated =
        MemoryBook.forgetMatching(current, fact)
          ?: return@runBlocking failure(
            // Two candidates and none are removed, which is the right failure: erasing the wrong
            // memory is not something the user can see has happened.
            "Nothing matched \"$fact\" closely enough to be sure which memory to remove. Read " +
              "back what is remembered and ask which one they mean."
          )
      repository.saveAssistantMemory(updated)
      sendProgress("Forgotten", inProgress = false)
      mapOf("status" to "succeeded", "hint" to "Confirm in one line that it is forgotten.")
    }
  }

  private suspend fun sendProgress(label: String, inProgress: Boolean) {
    executionContext
      ?.actionChannel
      ?.send(SkillProgressToolAction(label = label, inProgress = inProgress))
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)
}
