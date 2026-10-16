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

package com.lin.agent.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The moment a reminder is due, and the moment after a reboot.
 *
 * Both arrive here because both are the same job: find out what should have been said and say
 * it. The reboot half is the one that is easy to forget and impossible to notice - alarms do
 * not survive a restart, so without it every reminder set before a reboot would simply never
 * arrive, with the app still cheerfully listing it as pending.
 */
class ReminderReceiver : BroadcastReceiver() {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Deps {
    fun reminders(): ReminderBook
  }

  override fun onReceive(context: Context, intent: Intent) {
    val book = EntryPointAccessors.fromApplication(context, Deps::class.java).reminders()
    // The work is a settings read and a notification, which is far too slow for onReceive.
    // goAsync keeps the process alive for it; the window is ten seconds and this needs
    // milliseconds.
    val finish = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        when (intent.action) {
          ACTION_REMIND -> intent.getStringExtra(EXTRA_ID)?.let { book.fire(it) }
          // Also on a package replace: an update cancels the alarms exactly as a reboot does.
          Intent.ACTION_BOOT_COMPLETED,
          Intent.ACTION_MY_PACKAGE_REPLACED -> book.rearm()
          else -> Unit
        }
      } catch (failure: Throwable) {
        Log.w(TAG, "Reminder ${intent.action} failed", failure)
      } finally {
        finish.finish()
      }
    }
  }

  companion object {
    const val ACTION_REMIND = "com.lin.agent.REMIND"
    const val EXTRA_ID = "id"
    private const val TAG = "ReminderReceiver"
  }
}
