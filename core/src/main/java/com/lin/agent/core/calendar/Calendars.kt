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

package com.lin.agent.core.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What came of looking at the calendar. */
sealed interface CalendarRead {
  data class Ok(val events: List<CalEvent>) : CalendarRead

  /** Needs permission. Distinct from empty, because the fix is completely different. */
  data object NotPermitted : CalendarRead

  data class Failed(val why: String) : CalendarRead
}

/**
 * Reads what is in the phone's calendars.
 *
 * `Instances` rather than `Events`, and that is the whole reason this is short. A repeating
 * event is one row in `Events` with a recurrence rule that would have to be expanded by hand -
 * dates, exceptions, time zones and all. `Instances` is the provider doing that expansion
 * itself, which is both correct and somebody else's problem.
 *
 * Read-only. Nothing here writes to a calendar: an event Lin creates goes through the calendar
 * app's own editor, so the app holds no write permission to misuse.
 */
class Calendars(private val context: Context) {

  fun permitted(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
      PackageManager.PERMISSION_GRANTED

  /** Everything between [from] and [until], across every calendar on the phone. */
  suspend fun between(from: Long, until: Long): CalendarRead =
    withContext(Dispatchers.IO) {
      if (!permitted()) return@withContext CalendarRead.NotPermitted

      val uri =
        CalendarContract.Instances.CONTENT_URI.buildUpon()
          .let { ContentUris.appendId(it, from); ContentUris.appendId(it, until); it.build() }

      try {
        context.contentResolver
          .query(uri, PROJECTION, null, null, "${CalendarContract.Instances.BEGIN} ASC")
          .use { cursor ->
            if (cursor == null) return@withContext CalendarRead.Failed("The calendar did not answer.")
            val events = mutableListOf<CalEvent>()
            while (cursor.moveToNext()) {
              events.add(
                CalEvent(
                  title = cursor.getString(1).orEmpty(),
                  startMillis = cursor.getLong(2),
                  endMillis = cursor.getLong(3),
                  // The provider reports this as 0 or 1 rather than a boolean.
                  allDay = cursor.getInt(4) != 0,
                  location = cursor.getString(5).orEmpty(),
                )
              )
            }
            CalendarRead.Ok(events)
          }
      } catch (e: SecurityException) {
        // Revoked between the check above and the query, which is a real sequence on Android.
        CalendarRead.NotPermitted
      } catch (e: Throwable) {
        Log.w(TAG, "Could not read the calendar", e)
        CalendarRead.Failed("The calendar could not be read.")
      }
    }

  private companion object {
    const val TAG = "LinCalendars"

    val PROJECTION =
      arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_LOCATION,
      )
  }
}
