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

package com.lin.agent.core.media

import java.net.URLEncoder

/**
 * Getting a song to play in the app somebody actually uses.
 *
 * Android has a standard way to ask: `MEDIA_PLAY_FROM_SEARCH`, with the artist and title as
 * extras. Where an app answers it, that is the whole story - the app searches and plays, and
 * nothing here is needed. The trouble is that support is uneven and undocumented per app: one
 * will play, another will open a search page and wait, a third declares nothing at all. There
 * is no way to ask an app which it does.
 *
 * So this is the second line: a search link into the app, which gets somebody to the song they
 * asked for in one tap even where the intent does nothing. It is a short table of the apps
 * people actually use rather than anything clever, because a wrong URL opens a browser instead
 * of a music app and that is worse than not trying.
 */
fun searchUrlFor(packageName: String, query: String): String? {
  val encoded = URLEncoder.encode(query.trim(), "UTF-8")
  if (encoded.isEmpty()) return null
  return when (packageName) {
    "com.aspiro.tidal" -> "https://tidal.com/search?q=$encoded"
    "com.google.android.apps.youtube.music" -> "https://music.youtube.com/search?q=$encoded"
    "com.google.android.youtube" -> "https://www.youtube.com/results?search_query=$encoded"
    "com.spotify.music" -> "https://open.spotify.com/search/$encoded"
    "com.apple.android.music" -> "https://music.apple.com/search?term=$encoded"
    "deezer.android.app" -> "https://www.deezer.com/search/$encoded"
    "com.soundcloud.android" -> "https://soundcloud.com/search?q=$encoded"
    else -> null
  }
}

/**
 * Apps worth offering as the one to play music in, by package.
 *
 * Used only to sort and name what is actually installed - anything on the phone that can play
 * music is offered, whether it is in this list or not. Being here just means Lin knows its
 * name without asking, and knows a search link for it.
 */
val KNOWN_MUSIC_APPS: Map<String, String> =
  mapOf(
    "com.aspiro.tidal" to "TIDAL",
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "com.spotify.music" to "Spotify",
    "com.apple.android.music" to "Apple Music",
    "deezer.android.app" to "Deezer",
    "com.soundcloud.android" to "SoundCloud",
    "com.amazon.mp3" to "Amazon Music",
  )
