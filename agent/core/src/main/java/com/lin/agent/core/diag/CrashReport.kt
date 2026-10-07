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

package com.lin.agent.core.diag

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Records what killed the app, somewhere it can actually be read afterwards.
 *
 * `adb logcat` is the normal way to find out why an Android app closed, and it is exactly what is
 * unavailable when the app was installed from a release page onto a phone with no computer
 * attached. Without this the only possible bug report is "it closed".
 *
 * Two copies are written. One inside the app's private files, for the recovery screen to show. One
 * in `getExternalFilesDir`, which needs no permission and is browsable by any file manager at
 * `Android/data/<package>/files` - because the case that matters most is the app never managing to
 * draw anything, and a report only the app can open is no report at all then.
 *
 * It cannot catch everything, and the gap is the point of [LaunchHealth.mark]: a native crash or
 * an out-of-memory kill ends the process without running any Java code, so nothing here is
 * invoked and the report stays empty. An empty report beside a breadcrumb is still a diagnosis.
 */
object CrashReport {

  private const val FILE_NAME = "last-crash.txt"

  /** A trace far past this is loops, and the top of it is the useful part. */
  private const val MAX_CHARS = 16_000

  private val noted = ConcurrentHashMap.newKeySet<String>()

  /**
   * Installs the handler. Safe to call more than once.
   *
   * The previously installed handler always runs afterwards: it is the one that ends the process,
   * and skipping it would leave the app frozen rather than closed.
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
      // Anything thrown while recording would replace the real crash with a less useful one, so
      // the write is best-effort and the original handler runs either way.
      try {
        write(context, thread, error)
      } catch (e: Throwable) {
        Log.e(TAG, "Could not record the crash", e)
      }
      previous?.uncaughtException(thread, error)
    }
  }

  private fun write(context: Context, thread: Thread, error: Throwable) {
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val report = buildString {
      appendLine("Lin crashed at ${stamp()}")
      appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
      appendLine("Thread: ${thread.name}")
      appendLine("Got as far as: ${LaunchHealth.lastMilestone(context).ifEmpty { "unknown" }}")
      appendLine()
      append(trace)
    }
    save(context, report.take(MAX_CHARS))
  }

  /**
   * Adds [text] to the report, for a failure the app survived but the user should know about.
   *
   * A settings file that could not be read and was reset leaves the app running, which is the
   * point - but their preferences have silently gone, and they are owed an explanation rather than
   * discovering it one setting at a time. [key] makes the note idempotent, because the failing
   * read that produced it usually repeats on every access.
   */
  fun note(context: Context, key: String, text: String) {
    if (!noted.add(key)) return
    try {
      val existing = file(context).takeIf { it.exists() }?.readText().orEmpty()
      val addition = (if (existing.isEmpty()) "" else "\n") + "${stamp()} - $text\n"
      save(context, (existing + addition).take(MAX_CHARS))
    } catch (e: Throwable) {
      Log.e(TAG, "Could not record the note", e)
    }
  }

  /** The recorded report, or null when the last run ended normally. */
  fun read(context: Context): String? =
    try {
      file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
      null
    }

  /** Discards the report, once it has been seen. */
  fun clear(context: Context) {
    noted.clear()
    runCatching { file(context).delete() }
    runCatching { context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).delete() } }
  }

  private fun save(context: Context, text: String) {
    file(context).writeText(text)
    // Best-effort by construction: external storage can be unavailable, and failing to write the
    // second copy must not lose the first.
    try {
      val dir = context.getExternalFilesDir(null) ?: return
      if (dir.exists() || dir.mkdirs()) File(dir, FILE_NAME).writeText(text)
    } catch (e: Throwable) {
      Log.e(TAG, "Could not write the shared copy", e)
    }
  }

  private fun file(context: Context) = File(context.filesDir, FILE_NAME)

  private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

  private const val TAG = "LinCrashReport"
}
