/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import android.app.Application
import android.util.Log
import com.google.ai.edge.gallery.assistant.CrashReport
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.StoreHealth
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  /**
   * Everything here is wrapped, because a throw from `onCreate` is the worst crash this app has.
   *
   * It happens before any UI exists, so the recorded crash report cannot be shown and the user is
   * not offered a way out; and every one of these steps reads from disk, so whatever caused it is
   * still there on the next launch. That is an app that "keeps crashing non-stop" with nothing on
   * screen to say why and no way back in short of clearing the app's storage, which also throws
   * away a multi-gigabyte model download.
   *
   * None of this setup is load-bearing enough to be worth that. A missing theme means the default
   * theme. Reminders that did not load can load later. Analytics that did not initialise is not a
   * thing the user wants the app closed over. So each step is allowed to fail on its own, the
   * failure is recorded where the launch screen will show it, and the app opens.
   */
  override fun onCreate() {
    super.onCreate()
    // First, so that a crash in any of the setup below is still recorded. The app is installed
    // from a release page onto a phone with no computer attached, where logcat is not available
    // and an unrecorded crash can only ever be reported as "it closed".
    CrashReport.install(this)
    // And immediately after, so a failing settings store has somewhere to report itself from.
    StoreHealth.install(this)

    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    step("reminders") { notificationScheduleManager.initialize() }

    // Load saved theme.
    step("theme") { ThemeSettings.themeOverride.value = dataStoreRepository.readTheme() }

    step("analytics") {
      FirebaseApp.initializeApp(this)
      firebaseAnalytics?.setAnalyticsCollectionEnabled(dataStoreRepository.readFirebaseAnalytics())
    }
  }

  /** Runs one startup step, recording a failure rather than letting it close the app. */
  private inline fun step(what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Throwable) {
      Log.e(TAG, "Startup step '$what' failed", e)
      // Best-effort: this is already the handler for "something at startup went wrong".
      runCatching {
        CrashReport.note(
          context = this,
          key = "startup-$what",
          text =
            "Part of the app's start-up ($what) did not work this time, so the app opened " +
              "without it. ${e::class.java.simpleName}: ${e.message?.take(200).orEmpty()}",
        )
      }
    }
  }

  private companion object {
    const val TAG = "AGGalleryApplication"
  }
}
