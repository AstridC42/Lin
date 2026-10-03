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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

private const val TAG = "LinMemory"

/**
 * When the remembering happens.
 *
 * Two schedules, for two different failure modes.
 *
 * A conversation ending queues a one-off pass a few minutes later. The delay is the point: the
 * user has usually just closed the chat and may reopen it, and digesting a conversation they are
 * about to continue wastes the work and splits it in two. A few minutes is long enough that
 * coming back is rare and short enough that the engine is often still loaded.
 *
 * And a daily pass, which exists because the one-off is not reliable on its own. Placeholders
 * written when no model was loaded want upgrading; notes want cooling as they age; a conversation
 * queued just before a reboot wants picking up. The daily pass is what makes the vault converge
 * rather than depending on every one-off having fired.
 */
object MemoryMaintenance {

  private const val DIGEST_WORK = "lin-memory-digest"
  private const val DAILY_WORK = "lin-memory-daily"

  /**
   * How long after a conversation ends before it is digested.
   *
   * Short enough that the model is often still in memory, long enough that reopening the chat to
   * add one more thing does not produce two half-digests of one conversation.
   */
  const val DIGEST_DELAY_MINUTES = 4L

  /** Input key carrying the conversation that just ended. */
  const val KEY_CONVERSATION_ID = "conversation_id"

  /**
   * Records that [conversationId] is finished, and arranges for it to be remembered.
   *
   * The id travels as worker input rather than being written to the vault here, because the
   * place that knows a conversation has ended is `onDispose` - which cannot suspend, and which
   * runs while the screen is going away. Handing WorkManager a string is safe from there;
   * waiting on a DataStore write is not. The worker does the writing.
   */
  fun conversationEnded(context: Context, conversationId: String) {
    if (conversationId.isBlank()) return
    enqueueDigestPass(context, conversationId = conversationId)
  }

  /** Asks for a digest pass shortly. Replaces any pass already waiting, rather than stacking. */
  fun enqueueDigestPass(
    context: Context,
    delayMinutes: Long = DIGEST_DELAY_MINUTES,
    conversationId: String = "",
  ) {
    try {
      val request =
        OneTimeWorkRequestBuilder<MemoryDigestWorker>()
          .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
          .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
          .setInputData(workDataOf(KEY_CONVERSATION_ID to conversationId))
          .build()
      // APPEND_OR_REPLACE rather than REPLACE: two conversations ending in quick succession are
      // two conversations to remember, and REPLACE would drop the first one's id on the floor.
      WorkManager.getInstance(context)
        .enqueueUniqueWork(DIGEST_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    } catch (e: Exception) {
      // Memory is a convenience; failing to schedule it must never take the app down.
      Log.e(TAG, "Could not schedule the digest pass", e)
    }
  }

  /** The daily catch-up. Safe to call on every launch. */
  fun scheduleDailyPass(context: Context) {
    try {
      val request =
        PeriodicWorkRequestBuilder<MemoryDigestWorker>(1, TimeUnit.DAYS)
          .setConstraints(
            Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(false).build()
          )
          .build()
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    } catch (e: Exception) {
      Log.e(TAG, "Could not schedule the daily memory pass", e)
    }
  }

  /** Runs a pass now, for the "digest everything waiting" button on the memory screen. */
  fun runNow(context: Context) = enqueueDigestPass(context, delayMinutes = 0)
}
