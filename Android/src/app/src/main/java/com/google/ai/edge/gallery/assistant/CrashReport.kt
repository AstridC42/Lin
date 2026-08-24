/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.assistant

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AGCrashReport"
private const val CRASH_FILE = "last-crash.txt"

/** Longest report kept. A stack trace far past this is loops, and the top of it is the useful part. */
private const val MAX_REPORT_CHARS = 16_000

/**
 * Records the exception that killed the app, for reading back on the next launch.
 *
 * This exists because the app is installed from a release page onto a phone, by someone who has no
 * computer attached to it. `adb logcat` is the normal way to find out why an Android app closed,
 * and it is not available in that situation: the app simply disappears and the only report possible
 * is "it closed". Writing the trace where the next launch can show it turns that into something
 * specific enough to act on.
 *
 * It cannot catch everything, and the gap matters. A native crash or an out-of-memory kill - the
 * likely outcome of loading a model too large for the device - terminates the process without ever
 * running Java code, so nothing here is invoked. That case is covered separately, by the
 * `loading_model_name` marker the assistant writes before it loads a model.
 */
object CrashReport {

  /**
   * Installs the handler. Safe to call more than once.
   *
   * The previously installed handler is always invoked afterwards: it is the one that shows the
   * system's "app has stopped" dialog and ends the process, and skipping it would leave the app
   * frozen instead of closing.
   */
  fun install(context: Context) {
    val appContext = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous is Handler) return

    Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, previous))
  }

  private class Handler(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
  ) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
      // Anything thrown while recording the crash would replace the real one with a less useful
      // report, so the write is best-effort and the original handler runs regardless.
      try {
        write(context, thread, error)
      } catch (e: Throwable) {
        Log.e(TAG, "Could not record the crash", e)
      }
      previous?.uncaughtException(thread, error)
    }
  }

  private fun write(context: Context, thread: Thread, error: Throwable) {
    val stackTrace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val report = buildString {
      appendLine("Star Storyteller crashed at $when_")
      appendLine("App version: ${BuildConfigVersion.name} (${BuildConfigVersion.code})")
      appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
      appendLine("Thread: ${thread.name}")
      appendLine()
      append(stackTrace)
    }
    file(context).writeText(report.take(MAX_REPORT_CHARS))
  }

  /** The recorded crash, or null when the last run ended normally. */
  fun read(context: Context): String? =
    try {
      file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
      Log.e(TAG, "Could not read the recorded crash", e)
      null
    }

  /** Discards the recorded crash, once it has been shown. */
  fun clear(context: Context) {
    try {
      file(context).delete()
    } catch (e: Throwable) {
      Log.e(TAG, "Could not clear the recorded crash", e)
    }
  }

  private fun file(context: Context) = File(context.filesDir, CRASH_FILE)
}

/**
 * The app's version, read through a small indirection.
 *
 * `BuildConfig` is generated into the app's own package, and referencing it from here directly ties
 * this file to that package name; going through one object keeps the dependency in a single place.
 */
private object BuildConfigVersion {
  val name: String = com.google.ai.edge.gallery.BuildConfig.VERSION_NAME
  val code: Int = com.google.ai.edge.gallery.BuildConfig.VERSION_CODE
}
