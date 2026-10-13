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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lin.agent.core.memory.Digest
import com.lin.agent.core.memory.Memory
import com.lin.agent.core.memory.MemorySource
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.engine.EmbedPart
import com.lin.agent.engine.Message
import com.lin.agent.engine.TurnEvent
import com.lin.agent.engine.TurnRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * Reads a finished conversation for the few things worth keeping.
 *
 * Runs after the conversation, not during it, for two reasons. A model asked to both answer and
 * take notes does neither well; and the work is slow enough that doing it mid-turn would be
 * felt. By then the app is usually in the background, which is what a worker is for.
 *
 * Deliberately not foreground and deliberately not urgent. Nothing waits on this, so it runs
 * when the phone has nothing better to do - and if it never runs, the only thing lost is some
 * convenience, which is the right weight for a feature that writes things about somebody
 * without being asked each time.
 */
class DigestWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun memories(): MemoryStore

    fun embedders(): Embedders

    fun backends(): ChatBackends

    fun settings(): SettingsRepository
  }

  override suspend fun doWork(): Result =
    try {
      digest()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.w(TAG, "Digest failed", e)
      // Failure is silent on purpose: nothing asked for this, so nothing is waiting to be told
      // it did not happen. The log is cleared either way so it is not re-read later.
      ConversationLog(context).clear()
      Result.failure()
    }

  private suspend fun digest(): Result {
    val log = ConversationLog(context)
    val conversation = log.read()
    log.clear()
    if (conversation.isBlank()) return Result.success()

    val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)

    // Asked again here, not only where this was enqueued. The constraints mean a worker can sit
    // for hours before running, and the switch it was queued under is the user's answer to "may
    // you write things about me" - which they are entitled to change their mind about in the
    // meantime. The queued work is not a promise they are held to.
    if (!deps.settings().digestConversations.first()) return Result.success()

    val backend =
      (deps.backends().readiness() as? com.lin.agent.model.ChatReadiness.Ready)?.backend
        ?: return Result.success()

    val events =
      backend
        .converse(
          TurnRequest(
            messages = listOf(Message(Message.Role.USER, conversation)),
            systemPrompt = Digest.PROMPT,
            // Constrained to the schema, so the model fills a list rather than writing prose
            // that then has to be parsed back out of a summary.
            responseSchema = Digest.SCHEMA,
          )
        )
        .toList()

    val answer =
      events.filterIsInstance<TurnEvent.Done>().lastOrNull()?.fullText
        ?: events.filterIsInstance<TurnEvent.Token>().joinToString("") { it.text }

    val facts = Digest.parse(answer)
    if (facts.isEmpty()) return Result.success()

    val memories = deps.memories()
    var kept = 0
    deps.embedders().withEmbedder { embedder ->
      for (fact in facts) {
        val vector = embedder.embed(listOf(EmbedPart.Text(fact)))
        if (vector.isEmpty()) continue

        // Already known? A digest run after every conversation will keep rediscovering the
        // same few facts about somebody, and a memory screen with "Otto's sister is Mia" on it
        // nine times is one nobody reads.
        val existing =
          memories.search(
            vector,
            limit = 1,
            minScore = Digest.SAME_FACT,
            sources = setOf(MemorySource.TOLD, MemorySource.CONVERSATION),
          )
        if (existing.isNotEmpty()) continue

        memories.remember(Memory(text = fact, source = MemorySource.CONVERSATION), vector)
        kept++
      }
    }

    Log.i(TAG, "Kept $kept of ${facts.size} candidate facts")
    return Result.success()
  }

  private companion object {
    const val TAG = "LinDigestWorker"
  }
}
