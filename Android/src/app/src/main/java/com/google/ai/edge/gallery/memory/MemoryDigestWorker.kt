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

package com.google.ai.edge.gallery.memory

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "LinDigestWorker"

/**
 * The background half of remembering.
 *
 * Runs after a conversation is over, with nobody watching: writes the transcript to cold storage,
 * asks the model what the conversation was about, folds what it learned into the notes, and files
 * a digest linking the two together.
 *
 * Three things it is careful about, all of them because no one is there to notice.
 *
 * It never loses a conversation. The archive is written before the model is involved at all, and
 * a digest is always recorded - an extractive placeholder when no engine is loaded, upgraded by
 * a later run. A conversation that cannot be summarised today is still findable tomorrow.
 *
 * It never fights the user for the engine. If a turn is in flight, or no model is loaded, it
 * writes the placeholder and returns rather than waiting with a wake lock held.
 *
 * And it gives up eventually. A conversation that has failed to digest [MAX_ATTEMPTS] times
 * keeps its placeholder and leaves the queue, because a queue that only grows is a queue nobody
 * ever drains.
 */
class MemoryDigestWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun memoryVaultRepository(): MemoryVaultRepository

    fun chatSessionRepository(): ChatSessionRepository

    fun loadedModelRegistry(): LoadedModelRegistry
  }

  override suspend fun doWork(): Result {
    val deps =
      EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
    val vault = deps.memoryVaultRepository()
    val sessions = deps.chatSessionRepository()
    val registry = deps.loadedModelRegistry()
    val archive = ConversationArchive(applicationContext)
    val now = System.currentTimeMillis()

    return try {
      // The conversation that triggered this pass, if any. Queued here rather than by the caller
      // because the caller is a screen going away and cannot wait on a write.
      inputData.getString(MemoryMaintenance.KEY_CONVERSATION_ID)?.takeIf { it.isNotBlank() }?.let {
        vault.queueForDigest(it, now)
      }

      vault.sweep(now)

      val queue = vault.pending()
      if (queue.isEmpty()) {
        Log.d(TAG, "Nothing to digest")
        return Result.success()
      }

      val all = sessions.getAllChatSessions().associateBy { it.sessionId }
      for (item in queue) {
        val session = all[item.conversationId]
        if (session == null) {
          // The conversation was deleted before it could be digested. Nothing to remember.
          Log.d(TAG, "Dropping ${item.conversationId}: no such conversation")
          vault.dropPending(item.conversationId)
          continue
        }
        digestOne(
          session = session,
          attempts = item.attempts,
          vault = vault,
          registry = registry,
          archive = archive,
          nowMillis = now,
        )
      }
      Result.success()
    } catch (e: Exception) {
      Log.e(TAG, "Digest pass failed", e)
      // Retry rather than fail: the queue survives in the vault either way, and a transient
      // failure here would otherwise wait for the next conversation to be scheduled.
      Result.retry()
    }
  }

  private suspend fun digestOne(
    session: ChatSessionProto,
    attempts: Int,
    vault: MemoryVaultRepository,
    registry: LoadedModelRegistry,
    archive: ConversationArchive,
    nowMillis: Long,
  ) {
    val transcript = ConversationArchive.render(session)
    val archivePath = archive.write(session) ?: ""

    val model = registry.availableForBackgroundWork()
    val parsed =
      if (model == null) {
        null
      } else {
        Log.d(TAG, "Digesting ${session.sessionId} with ${model.name}")
        MemoryDigester().digest(model, transcript)
      }

    if (parsed != null) {
      vault.recordDigest(
        conversationId = session.sessionId,
        parsed = parsed,
        archivePath = archivePath,
        messageCount = session.messagesCount,
        modelName = session.originalModel,
        startedAtMillis = session.timestampMs,
        endedAtMillis = nowMillis,
        extractive = false,
        nowMillis = nowMillis,
      )
      vault.dropPending(session.sessionId)
      return
    }

    // No model, or it could not answer. Record what can be recorded without one, so the
    // conversation is never only in the queue.
    val fallback =
      ExtractiveDigest.from(
        userMessages = session.messagesList.filter { it.side == ChatSideProto.CHAT_SIDE_USER }.map { it.content },
        modelMessages = session.messagesList.filter { it.side == ChatSideProto.CHAT_SIDE_MODEL }.map { it.content },
      )
    if (fallback != null) {
      vault.recordDigest(
        conversationId = session.sessionId,
        parsed = fallback,
        archivePath = archivePath,
        messageCount = session.messagesCount,
        modelName = session.originalModel,
        startedAtMillis = session.timestampMs,
        endedAtMillis = nowMillis,
        extractive = true,
        nowMillis = nowMillis,
      )
    }

    // The queue entry stays, so a later pass with a model loaded can replace the placeholder
    // with a real summary - but the attempt is counted, so a conversation that can never be
    // summarised stops being retried rather than sitting in the queue for ever.
    if (attempts + 1 < MAX_ATTEMPTS) {
      vault.recordDigestAttempt(session.sessionId)
    } else {
      Log.d(TAG, "Giving up on a real summary for ${session.sessionId}")
      vault.dropPending(session.sessionId)
    }
  }

  companion object {
    /** How many passes may fail before a conversation keeps its placeholder summary for good. */
    const val MAX_ATTEMPTS = 6
  }
}
