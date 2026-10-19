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

import com.lin.agent.core.memory.MemorySource
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.EmbedPart
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult

/**
 * Finds photos by what is in them.
 *
 * The same vector space as memory, which is the whole reason the 740M multimodal embedder was
 * chosen over the 164 MB text-only one: a description and a picture land in the same 768
 * dimensions, so "the one from the beach" is one query rather than a caption pipeline and a
 * join.
 *
 * The matches go to the screen as pictures and the model is told only how many there are. It
 * cannot see them, and handing it filenames to describe would produce confident sentences about
 * images nobody checked - which is the same failure as inventing a memory, with the evidence
 * sitting right there unexamined.
 */
class FindPhotoTool(
  private val memories: MemoryStore,
  private val embedders: Embedders,
  private val attachments: Attachments,
  private val context: android.content.Context,
) : AgentTool {

  override val name = "find_photo"

  override val description =
    "Find photos on this phone by what is in them - a place, an object, a scene. The pictures " +
      "are shown to the user directly. Set look to true to be shown the best match yourself, " +
      "when the question is about what is in the photo rather than which photo it is."

  override val parameters =
    listOf(
      ToolParameter(
        name = "describe",
        type = ParamType.STRING,
        description = "What is in the photo, in plain words: 'a dog on a beach', " +
          "'a whiteboard with diagrams'.",
      ),
      ToolParameter(
        name = "look",
        type = ParamType.BOOLEAN,
        description =
          "True to look at the best match yourself - for 'what does the sign say' rather " +
            "than 'show me the beach photo'.",
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
      "Use find_photo to look through the user's pictures; they appear on screen. Set its " +
      "look argument when the question is about what is IN a photo - the best match is then " +
      "shown to you and you may describe it. Without it you have not seen them, so never " +
      "describe what is in one."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val describe = (arguments["describe"] as? String)?.trim().orEmpty()
    if (describe.isEmpty()) return ToolResult.Failed("There was nothing to look for.")

    val indexed = memories.countFrom(MemorySource.PHOTO)
    if (indexed == 0) {
      return ToolResult.Failed(
        "No photos have been indexed yet. Tell the user they can start that in settings, and " +
          "that it runs on the phone - nothing is uploaded."
      )
    }

    val vector =
      embedders.withEmbedder { it.embed(listOf(EmbedPart.Text(describe))) }
        ?: return ToolResult.Failed(
          "Searching photos needs the embedding model, which is not on this phone."
        )
    if (vector.isEmpty()) return ToolResult.Failed("The embedding model could not read that.")

    val found =
      memories.search(
        vector,
        limit = MAX_SHOWN,
        // Looser than memory's floor. A picture is self-evidently right or wrong the moment it
        // is on screen, so a weak match costs a glance - where a weak *memory* gets stated as
        // fact and costs the truth. The thresholds differ because the failure differs.
        minScore = PHOTO_MIN_SCORE,
        sources = setOf(MemorySource.PHOTO),
      )

    if (found.isEmpty()) {
      return ToolResult.Ok(
        "No photo matched that out of $indexed indexed. Say so; do not describe one anyway."
      )
    }

    // Only when asked, and only the best one. Every photo on screen costs the user a glance;
    // every photo sent to the model costs a slice of a context window that has to hold the
    // conversation too, and on-device it stays there for the rest of the conversation.
    val look = arguments["look"] as? Boolean ?: false
    val best =
      if (!look) null
      else
        found.firstOrNull()?.memory?.reference?.takeIf { it.isNotBlank() }?.let { reference ->
          attachments.picture(android.net.Uri.parse(reference), context).getOrNull()
        }

    return ToolResult.Ok(
      text =
        if (best != null) {
          "${found.size} photo(s) matched and are on screen. The best match is being shown to " +
            "you as well - answer from what you can actually see in it."
        } else {
          "${found.size} photo(s) matched and are already on screen for the user. You have not " +
            "been shown them, so do not describe them. Say they are shown, and offer to " +
            "narrow it down."
        },
      shows = found.map { it.memory.reference },
      looksAt = listOfNotNull(best),
    )
  }

  private companion object {
    const val MAX_SHOWN = 6
    const val PHOTO_MIN_SCORE = 0.2f
  }
}
