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

import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.Attachment
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The last photo the user sent in the conversation, and requests from Lin to open it in the
 * cut-out editor. The conversation screen listens and opens the editor.
 */
@Singleton
class PhotoDesk @Inject constructor() {
  @Volatile var lastPicture: Attachment.Picture? = null

  private val _cutOut = MutableSharedFlow<Attachment.Picture>(extraBufferCapacity = 1)
  val cutOut: SharedFlow<Attachment.Picture> = _cutOut.asSharedFlow()

  fun requestCutOut(picture: Attachment.Picture): Boolean = _cutOut.tryEmit(picture)
}

/** Removes the background of (cuts out) the photo the user sent, in Lin's own editor. */
class CutOutTool(private val desk: PhotoDesk) : AgentTool {
  override val name = "cut_out_photo"

  override val description =
    "Remove the background from the photo the user sent (cut out the person or object), or blur " +
      "the background. Opens Lin's cut-out editor on the photo with the main subject selected."

  override val parameters = emptyList<ToolParameter>()

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
    "Use cut_out_photo when the user asks to remove or blur a photo's background, cut something " +
      "out of a photo, or make a sticker from it. It works on the last photo they sent in this chat."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val picture =
      desk.lastPicture
        ?: return ToolResult.Failed(
          "No photo has been sent in this chat yet. Ask the user to attach the photo with the + button and ask again."
        )
    if (!desk.requestCutOut(picture)) return ToolResult.Failed("The cut-out editor could not be opened just now.")
    return ToolResult.Ok(
      "Done - the cut-out editor is open on their photo with the main subject selected. Tell them they " +
        "can tap to add or remove parts, then save it with a clear or blurred background, or share it."
    )
  }
}
