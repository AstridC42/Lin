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

package com.google.ai.edge.gallery.data

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.assistant.CrashReport

/**
 * What to do when a settings file cannot be read.
 *
 * Every preference in this app lives in a proto DataStore, and until now a store that could not be
 * read threw, from whichever call asked for it. Several of those calls are on the path the app
 * takes before it has drawn anything: the theme and the analytics flag are read in
 * `Application.onCreate`, and the assistant's own settings are read to find out whether the
 * previous launch died while loading a model.
 *
 * A throw from there is not a bug report, it is an app that cannot start. It closes before any UI
 * exists, so nothing can show what happened and nothing can offer a way out - and because the
 * cause is on disk, the next launch does exactly the same thing. That is the difference between
 * one crash and an app that "keeps crashing non-stop", and the only way out of it from a phone is
 * to clear the app's storage, which also throws away the downloaded model.
 *
 * Two things can put a store in that state and neither is exotic:
 *
 *  - **A full disk.** The user has just downloaded several gigabytes of model weights. A write
 *    that cannot allocate fails, and on some filesystems leaves a file that no longer parses.
 *  - **A process killed mid-write.** `updateData` writes to a temporary file and renames it,
 *    which is atomic, but the surrounding bookkeeping is not immune - and the app deliberately
 *    writes a marker to the settings store immediately before loading a model, which is the exact
 *    moment the system is most likely to kill it for memory.
 *
 * So: a store that cannot be read is reset to its defaults and the app carries on. Losing
 * somebody's preferences is a real cost, not a free fix, which is why it is never silent - every
 * reset and every failed read is written into the report the launch screen already shows and waits
 * for the user to dismiss. Preferences can be set again. An app that will not open cannot be.
 */
object StoreHealth {

  @Volatile private var appContext: Context? = null

  /** Called once, as early as possible, so later failures have somewhere to be recorded. */
  fun install(context: Context) {
    appContext = context.applicationContext
  }

  /**
   * Records that [store] had to be reset to its defaults because its file could not be read.
   *
   * Phrased for the person holding the phone rather than for a stack trace: what was lost, and
   * the likely reason, since a full disk is both the most likely cause and the one they can do
   * something about.
   */
  fun recordReset(store: String, error: Throwable?) {
    Log.w(TAG, "Resetting unreadable store '$store'", error)
    note(
      key = "reset-$store",
      text =
        "Your $store could not be read and has been reset to its defaults. This usually means " +
          "the phone ran out of storage space while saving. The app works; what was saved there " +
          "is gone. ${describe(error)}",
    )
  }

  /**
   * Records that reading [store] failed, without resetting it.
   *
   * Distinct from a reset on purpose. A corrupt file is replaced once and then read normally; a
   * failure here is transient - a full disk, most often - so the value falls back to its default
   * for this one read and the file is left alone, because the settings in it may still be fine
   * once there is room to read them.
   */
  fun recordReadFailure(store: String, error: Throwable?) {
    Log.w(TAG, "Could not read store '$store'", error)
    note(
      key = "read-$store",
      text =
        "Your $store could not be read, so the app is using default values for now. This usually " +
          "means the phone has run out of storage space. Nothing has been deleted. " +
          "${describe(error)}",
    )
  }

  /** Records that a change could not be saved, so the user does not think it took effect. */
  fun recordWriteFailure(store: String, error: Throwable?) {
    Log.w(TAG, "Could not write store '$store'", error)
    note(
      key = "write-$store",
      text =
        "A change to your $store could not be saved. This usually means the phone has run out of " +
          "storage space. ${describe(error)}",
    )
  }

  private fun note(key: String, text: String) {
    val context = appContext ?: return
    // Best-effort by construction: this is the handler for "the disk is not working", so it has
    // to assume its own write may fail too, and a throw from here would re-create the crash it
    // exists to replace.
    try {
      CrashReport.note(context, key = key, text = text)
    } catch (e: Throwable) {
      Log.e(TAG, "Could not record the store problem", e)
    }
  }

  /** The exception's own words, kept short. Useful when the guess above is the wrong guess. */
  private fun describe(error: Throwable?): String {
    val message = error?.message?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
    return "(${error::class.java.simpleName}: ${message.take(200)})"
  }

  private const val TAG = "AGStoreHealth"
}
