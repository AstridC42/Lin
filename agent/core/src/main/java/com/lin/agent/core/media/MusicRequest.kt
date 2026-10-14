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

/**
 * What to play, split into the parts a music app's search understands.
 *
 * [query] is what gets typed into the search box and is never empty. [title] and [artist] are
 * filled in when the request said which was which, because Android's play-from-search intent
 * takes them as separate extras and an app given them does a better job than one given a
 * sentence.
 */
data class MusicRequest(val query: String, val title: String = "", val artist: String = "")

/**
 * Reads "play Stan by Eminem" into its parts.
 *
 * The splitting is on " by ", which is how people say it in English and is the only separator
 * worth trusting: "Stan by Eminem" is a title and an artist, while "Eminem" alone is just a
 * query and guessing which half is which would be worse than not splitting at all.
 *
 * The leading verb comes off because it is addressed to Lin rather than to the music app -
 * searching Tidal for "play Stan" finds a song called "Play" more often than it finds Stan.
 * Trailing noise like "on tidal" or "in youtube music" goes too: which app to use is a
 * different question, answered by the setting, and leaving it in the query searches for it.
 */
fun musicRequest(said: String): MusicRequest? {
  var text = said.trim().trim('"', '\'', '.', '!', '?').trim()
  if (text.isEmpty()) return null

  for (verb in VERBS) {
    val lower = text.lowercase()
    // The verb on its own is not a request to play anything, so it comes off too and leaves
    // nothing - which is the honest answer to "play".
    if (lower == verb) return null
    if (lower.startsWith("$verb ")) {
      text = text.substring(verb.length).trim()
      break
    }
  }
  text = text.removeAppSuffix()
  if (text.isEmpty()) return null

  val separator = text.lowercase().lastIndexOf(" by ")
  if (separator <= 0) return MusicRequest(query = text)

  val title = text.substring(0, separator).trim().trim('"', '\'').trim()
  val artist = text.substring(separator + 4).trim().trim('"', '\'').trim()
  if (title.isEmpty() || artist.isEmpty()) return MusicRequest(query = text)

  // The query keeps both halves: a search for "Stan Eminem" finds the right song, while a
  // search for only the title finds every cover of it.
  return MusicRequest(query = "$title $artist", title = title, artist = artist)
}

/**
 * Takes "on tidal" off the end.
 *
 * Only at the end, and only after a preposition, so that a song actually called "On Repeat" or
 * a band called In Flames survives.
 */
private fun String.removeAppSuffix(): String {
  val lower = lowercase()
  for (preposition in listOf(" on ", " in ", " with ", " using ")) {
    val at = lower.lastIndexOf(preposition)
    if (at <= 0) continue
    val tail = lower.substring(at + preposition.length).trim()
    if (tail in KNOWN_APPS) return substring(0, at).trim()
  }
  return this
}

private val VERBS = listOf("play", "put on", "start", "listen to", "stick on")

/**
 * Names of music apps as people say them, for stripping only.
 *
 * Deliberately a short list of names rather than anything clever: the cost of missing one is a
 * slightly worse search, and the cost of being clever is a song whose title is a word in this
 * list being cut in half.
 */
private val KNOWN_APPS =
  setOf(
    "tidal",
    "spotify",
    "youtube",
    "youtube music",
    "yt music",
    "ytmusic",
    "apple music",
    "deezer",
    "soundcloud",
    "amazon music",
    "qobuz",
    "plex",
    "navidrome",
  )
