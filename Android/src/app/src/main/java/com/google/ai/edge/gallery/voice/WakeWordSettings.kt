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

package com.google.ai.edge.gallery.voice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.ai.edge.gallery.MainActivity

/** Small helpers shared between the wake word tile and the assistant's settings UI. */
object WakeWordSettings {

  /** Opens the app, for cases like the tile needing a runtime permission it cannot ask for. */
  fun openAppPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      2,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  /** The phrase to listen for, falling back to the default when none is set. */
  fun phraseOrDefault(stored: String): String =
    stored.trim().ifEmpty { DEFAULT_WAKE_PHRASE }
}
