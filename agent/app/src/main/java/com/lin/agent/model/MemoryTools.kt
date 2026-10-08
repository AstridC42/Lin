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

package com.lin.agent.model

import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemorySource
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.core.models.ModelRole
import com.lin.agent.core.models.ModelStore
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.EmbedPart
import com.lin.agent.engine.Embedder
import com.lin.agent.engine.LiteRtEmbedder
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

/**
 * Loads the embedder for a burst and puts it away again.
 *
 * The chat model is resident and the embedder is not, which is the memory budget working as
 * designed: embedding happens for one search or one indexing run and then nothing for hours,
 * and half a gigabyte held in between comes out of the chat model's headroom on a phone that
 * has exactly enough for one of them.
 *
 * Serialised, because two bursts at once would load it twice.
 */
@Singleton
class Embedders @Inject constructor(private val models: ModelStore) {

  private val lock = Mutex()

  /**
   * Runs [work] with an embedder, or returns null if there is no embedding model downloaded.
   *
   * Closed afterwards whatever happens - a failed search that leaves the weights resident is
   * the same cost as a successful one that does.
   */
  suspend fun <T> withEmbedder(work: suspend (Embedder) -> T): T? =
    lock.withLock {
      val model = models.available(ModelRole.EMBED).firstOrNull() ?: return@withLock null
      LiteRtEmbedder(
          modelPath = models.fileFor(model).absolutePath,
          cacheDir = models.cacheDirectory().absolutePath,
        )
        .use { work(it) }
    }
}

/** Shared wording, so both tools say the same thing about a missing embedder. */
private const val NO_EMBEDDER =
  "Memory needs the embedding model, which is not on this phone yet. Tell the user they can " +
    "download EmbeddingGemma 2 in settings, and carry on without it."

/**
 * Writes something down so it survives the conversation.
 *
 * An explicit tool rather than a background digest reading every conversation, which is where
 * this is meant to end up. The difference matters for more than effort: a digest decides on
 * somebody's behalf what about their day is worth keeping, and getting that wrong is a privacy
 * problem rather than a bug. Asking first is the version worth shipping before the version that
 * assumes.
 */
class RememberTool(
  private val memories: MemoryStore,
  private val embedders: Embedders,
) : AgentTool {

  override val name = "remember"

  override val description =
    "Write down one fact worth keeping past this conversation - something the user said to " +
      "remember, or a lasting preference. Not for things only relevant right now."

  override val parameters =
    listOf(
      ToolParameter(
        name = "fact",
        type = ParamType.STRING,
        description = "The thing to remember, as one self-contained sentence that will still " +
          "make sense in six months with no conversation around it.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val fact = (arguments["fact"] as? String)?.trim().orEmpty()
    if (fact.isEmpty()) return ToolResult.Failed("There was nothing to remember.")

    val vector =
      embedders.withEmbedder { it.embed(listOf(EmbedPart.Text(fact))) }
        ?: return ToolResult.Failed(NO_EMBEDDER)
    if (vector.isEmpty()) return ToolResult.Failed("The embedding model could not read that.")

    val id =
      memories.remember(Memory(text = fact, source = MemorySource.TOLD), vector)
    return if (id < 0) ToolResult.Failed("That could not be saved.")
    else ToolResult.Ok("Remembered: $fact")
  }
}

/**
 * Looks for something remembered earlier.
 *
 * Returns nothing rather than the closest thing it has, which is the whole discipline of this
 * tool. Similarity search always ranks; without a floor every query comes back with its five
 * nearest memories however unrelated, and a model handed three irrelevant "memories" will state
 * them as fact in the user's own words. Nothing downstream marks a false memory as false.
 */
class RecallTool(
  private val memories: MemoryStore,
  private val embedders: Embedders,
) : AgentTool {

  override val name = "recall"

  override val description =
    "Look for something remembered from an earlier conversation. Use it when the user refers " +
      "to something you were not told in this one."

  override val parameters =
    listOf(
      ToolParameter(
        name = "about",
        type = ParamType.STRING,
        description = "What to look for, in the user's own words where possible.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val about = (arguments["about"] as? String)?.trim().orEmpty()
    if (about.isEmpty()) return ToolResult.Failed("There was nothing to look for.")

    val vector =
      embedders.withEmbedder { it.embed(listOf(EmbedPart.Text(about))) }
        ?: return ToolResult.Failed(NO_EMBEDDER)
    if (vector.isEmpty()) return ToolResult.Failed("The embedding model could not read that.")

    val found = memories.search(vector, sources = setOf(MemorySource.TOLD, MemorySource.CONVERSATION))
    if (found.isEmpty()) {
      // Said plainly, and the instruction matters as much as the fact: a model told "nothing
      // found" still tends to produce something plausible unless it is told not to.
      return ToolResult.Ok(
        "Nothing remembered about that. Say so rather than guessing, and ask if you need to know."
      )
    }

    return ToolResult.Ok(
      buildString {
        append("Remembered:")
        found.forEach { append("\n- ").append(it.memory.text) }
      }
    )
  }
}
