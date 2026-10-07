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

package com.lin.agent.core.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin.agent.core.diag.CrashReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Settings, with a corruption handler from the first line rather than added after it bit.
 *
 * The app this replaces had nine proto stores and not one of them had a `corruptionHandler`, so an
 * unreadable file threw on every read, for ever. Several of those reads sat on the path before the
 * first frame, which is how one crash became an app that would not open at all.
 *
 * Two ordinary things put a store in that state: a full disk - likely, on a phone that has just
 * taken several gigabytes of model weights - and a process killed mid-write. Neither is exotic and
 * neither should be fatal.
 *
 * So an unreadable file is replaced with defaults, and reads never throw. It is never silent:
 * every reset goes into the report the recovery screen shows, because losing somebody's
 * preferences is a real cost rather than a free fix. Preferences can be set again. An app that
 * will not open cannot be.
 */
private val Context.dataStore by
  preferencesDataStore(
    name = "settings",
    corruptionHandler =
      ReplaceFileCorruptionHandler {
        Log.w(TAG, "Settings could not be read; resetting to defaults", it)
        emptyPreferences()
      },
  )

private const val TAG = "LinSettings"

/** Keys live here so a typo is a compile error rather than a silently absent setting. */
object SettingsKeys {
  /** Which on-device model to load. Empty means "not chosen yet". */
  val MODEL_NAME = stringPreferencesKey("model_name")

  /** Where the Ollama server is, as a full base URL. Empty means "not configured". */
  val OLLAMA_URL = stringPreferencesKey("ollama_url")
}

class SettingsRepository(private val context: Context) {

  /**
   * Reads one setting as a flow, with [default] on any failure.
   *
   * `catch` rather than a bare flow: the corruption handler covers a file that cannot be parsed,
   * and this covers everything else that can make a read fail - a full disk being the likely one.
   * The failure is reported and the default is served, so a disk problem degrades the app instead
   * of closing it.
   */
  fun <T> read(key: Preferences.Key<T>, default: T): Flow<T> =
    context.dataStore.data
      .catch { error ->
        Log.w(TAG, "Could not read ${key.name}", error)
        CrashReport.note(
          context = context,
          key = "read-settings",
          text =
            "Your settings could not be read, so defaults are in use for now. This usually means " +
              "the phone has run out of storage space. Nothing has been deleted.",
        )
        emit(emptyPreferences())
      }
      .map { it[key] ?: default }

  /** Writes one setting, recording a failure rather than throwing it at the caller. */
  suspend fun <T> write(key: Preferences.Key<T>, value: T) {
    try {
      context.dataStore.edit { it[key] = value }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not write ${key.name}", error)
      CrashReport.note(
        context = context,
        key = "write-settings",
        text =
          "A change to your settings could not be saved. This usually means the phone has run " +
            "out of storage space.",
      )
    }
  }
}
