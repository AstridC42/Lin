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

/**
 * Settings, exposed by name.
 *
 * Deliberately not a generic `read(key, default)` API. That shape leaked `Preferences.Key` out of
 * this module, which meant every caller needed DataStore on its compile classpath and the storage
 * choice stopped being an implementation detail - the compiler said so immediately, which is the
 * boundary doing its job. Named accessors keep DataStore inside `core`, so swapping it for a proto
 * store or a database later touches this file and nothing else.
 */
class SettingsRepository(private val context: Context) {

  /**
   * Where the model server is, as a full base URL. Empty means not configured.
   *
   * A whole URL rather than a host and a port, because it has to accept both ways this server gets
   * reached: `http://192.168.1.50:11434` on the home network and `https://ollama.example.com`
   * through a tunnel from outside. Those differ in scheme, port and host shape, so storing the
   * parts separately would only have to reassemble them.
   */
  val serverUrl: Flow<String> = read(SERVER_URL, "")

  suspend fun setServerUrl(value: String) = write(SERVER_URL, value)

  /**
   * An optional header sent with every request to the server, as `Name: value`.
   *
   * Here from the start rather than added later, because a tunnel puts the server on the public
   * internet and Ollama has no authentication of its own: without something in front of it,
   * anyone who learns the hostname can use the GPU and read the prompts. A Cloudflare Access
   * service token or a bearer token goes here.
   */
  val serverAuthHeader: Flow<String> = read(SERVER_AUTH_HEADER, "")

  suspend fun setServerAuthHeader(value: String) = write(SERVER_AUTH_HEADER, value)

  /** Which on-device chat model the user chose, by name. Empty means "decide from the device". */
  val chatModelName: Flow<String> = read(CHAT_MODEL_NAME, "")

  suspend fun setChatModelName(value: String) = write(CHAT_MODEL_NAME, value)

  /**
   * Which of the server's models to send work to, by the name the server uses: `qwen3:32b`.
   *
   * Stored by name rather than by anything structural on purpose. The server's list is the
   * server's to change - somebody pulls a model, removes another - so the only durable reference
   * is the name, and a name that is no longer there has to read as "pick again" rather than as a
   * broken setting. Empty means nothing has been picked.
   */
  val serverModelName: Flow<String> = read(SERVER_MODEL_NAME, "")

  suspend fun setServerModelName(value: String) = write(SERVER_MODEL_NAME, value)

  /**
   * Reads one setting, with [default] on any failure.
   *
   * `catch` rather than a bare flow: the corruption handler above covers a file that cannot be
   * parsed, and this covers everything else that can make a read fail - a full disk being the
   * likely one on a phone that has just taken gigabytes of weights. The failure is reported and
   * the default served, so a disk problem degrades the app rather than closing it.
   */
  private fun <T> read(key: Preferences.Key<T>, default: T): Flow<T> =
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

  /** Writes one setting, recording a failure rather than throwing it at a switch being tapped. */
  private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
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

  private companion object {
    val SERVER_URL = stringPreferencesKey("ollama_url")
    val SERVER_AUTH_HEADER = stringPreferencesKey("ollama_auth_header")
    val CHAT_MODEL_NAME = stringPreferencesKey("chat_model_name")
    val SERVER_MODEL_NAME = stringPreferencesKey("server_model_name")
  }
}
