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
import com.lin.agent.core.memory.alreadyHeld
import com.lin.agent.core.memory.digestReport
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
      runCatching {
        EntryPointAccessors.fromApplication(context, Deps::class.java)
          .settings()
          .setLastDigest(digestReport(System.currentTimeMillis(), 0, 0, "it failed: ${e.message ?: e::class.java.simpleName}"))
      }
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
    val settings = deps.settings()
    val now = System.currentTimeMillis()
    suspend fun report(kept: Int, found: Int, why: String = "") =
      runCatching { settings.setLastDigest(digestReport(now, kept, found, why)) }

    // Asked again here, not only where this was enqueued. The constraints mean a worker can sit
    // for hours before running, and the switch it was queued under is the user's answer to "may
    // you write things about me" - which they are entitled to change their mind about in the
    // meantime. The queued work is not a promise they are held to.
    if (!settings.digestConversations.first()) return Result.success()

    val backend =
      (deps.backends().readiness() as? com.lin.agent.model.ChatReadiness.Ready)?.backend
        ?: run {
          report(0, 0, "no model was ready to read the conversation")
          return Result.success()
        }

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

    events.filterIsInstance<TurnEvent.Failed>().firstOrNull()?.let { failed ->
      report(0, 0, "the model said: ${failed.message.take(120)}")
      return Result.success()
    }

    val answer =
      events.filterIsInstance<TurnEvent.Done>().lastOrNull()?.fullText
        ?: events.filterIsInstance<TurnEvent.Token>().joinToString("") { it.text }

    val facts = Digest.parse(answer)
    if (facts.isEmpty()) {
      report(0, 0)
      return Result.success()
    }

    val memories = deps.memories()
    val held = memories.all(limit = 1_000).map { it.text }.toMutableList()
    var kept = 0

    // Kept whether or not there is an embedding model. This used to run only inside
    // withEmbedder, which returns null when no embedding model is downloaded - so without one
    // the loop never ran, every fact from every conversation was thrown away, and the worker
    // reported success. "Remember X" was fixed the same way long ago; this half was missed.
    // A fact without a vector is still kept and shown, and gets one when a model arrives.
    val withVectors =
      deps.embedders().withEmbedder { embedder ->
        facts.map { fact -> fact to runCatching { embedder.embed(listOf(EmbedPart.Text(fact))) }.getOrDefault(FloatArray(0)) }
      } ?: facts.map { it to FloatArray(0) }

    for ((fact, vector) in withVectors) {
      // Already known? A digest after every conversation keeps rediscovering the same few
      // facts, and a memory screen with the same sentence on it nine times is one nobody
      // reads. By meaning when there is a vector to compare, by words always.
      if (alreadyHeld(fact, held)) continue
      if (vector.isNotEmpty()) {
        val similar =
          memories.search(
            vector,
            limit = 1,
            minScore = Digest.SAME_FACT,
            sources = setOf(MemorySource.TOLD, MemorySource.CONVERSATION),
          )
        if (similar.isNotEmpty()) continue
      }
      if (memories.remember(Memory(text = fact, source = MemorySource.CONVERSATION), vector) >= 0) {
        held.add(fact)
        kept++
      }
    }

    report(kept, facts.size)
    Log.i(TAG, "Kept $kept of ${facts.size} candidate facts")
    return Result.success()
  }

  private companion object {
    const val TAG = "LinDigestWorker"
  }
}
