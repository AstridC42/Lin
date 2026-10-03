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

package com.google.ai.edge.gallery.car

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.AssistantSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "AGCarSettings"

/**
 * Reaches the app's settings from the car screen.
 *
 * A `Screen` is constructed by the Car App Library rather than by Hilt, so it has no injected
 * dependencies. Going through an entry point gets the same singleton repository the rest of the
 * app uses, which matters: a setting changed on the car screen has to be the setting the phone
 * reads back, not a second copy of it.
 */
object CarSettings {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface DataStoreEntryPoint {
    fun dataStoreRepository(): DataStoreRepository
  }

  private fun repository(context: Context): DataStoreRepository? =
    try {
      EntryPointAccessors.fromApplication(
          context.applicationContext,
          DataStoreEntryPoint::class.java,
        )
        .dataStoreRepository()
    } catch (e: Throwable) {
      // The car host can bind the service before the application object is fully up. Failing to
      // read a setting should grey out a row, not take down the car screen.
      Log.e(TAG, "Settings are not available yet", e)
      null
    }

  fun readAssistantSettings(context: Context): AssistantSettings? =
    try {
      repository(context)?.readAssistantSettings()
    } catch (e: Throwable) {
      Log.e(TAG, "Could not read the assistant settings", e)
      null
    }

  fun saveAssistantSettings(context: Context, settings: AssistantSettings) {
    try {
      repository(context)?.saveAssistantSettings(settings)
    } catch (e: Throwable) {
      Log.e(TAG, "Could not save the assistant settings", e)
    }
  }
}
