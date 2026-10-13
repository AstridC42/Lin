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

import android.content.Context
import android.util.Log
import com.lin.agent.engine.Message
import java.io.File

/**
 * One conversation, handed to the digest and then gone.
 *
 * A file rather than work input data, because a conversation is easily larger than the ten
 * kilobytes that can be passed to a worker - and a digest that silently skipped long
 * conversations would be worst exactly where it is most useful.
 *
 * Written only when a digest is about to run and deleted as soon as it is read, successfully or
 * not. The app does not otherwise keep conversations on disk, and a feature that writes notes
 * about somebody should not quietly start keeping transcripts as well.
 */
class ConversationLog(context: Context) {

  private val file = File(context.filesDir, NAME)

  /** Replaces whatever was there. An undigested older conversation is not worth keeping. */
  fun write(messages: List<Message>) {
    runCatching {
        file.writeText(
          messages.joinToString("\n") { message ->
            val who =
              when (message.role) {
                Message.Role.USER -> "Them"
                Message.Role.ASSISTANT -> "You"
                Message.Role.SYSTEM -> "System"
                // A tool result is what the app did, not what either of them said. Labelled so
                // the model does not read it as the person speaking and remember it as a fact
                // about them.
                Message.Role.TOOL -> "Tool ${message.toolName}"
              }
            "$who: ${message.text}"
          }
        )
      }
      .onFailure { Log.w(TAG, "Could not write the conversation for digesting", it) }
  }

  fun read(): String =
    runCatching { if (file.exists()) file.readText() else "" }
      .onFailure { Log.w(TAG, "Could not read the conversation", it) }
      .getOrDefault("")

  fun clear() {
    runCatching { file.delete() }
  }

  private companion object {
    const val TAG = "LinConversationLog"
    const val NAME = "to-digest.txt"
  }
}
