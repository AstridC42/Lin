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

package com.lin.agent.core.apps

/** An installed app, as far as matching a spoken name is concerned. */
data class InstalledApp(val label: String, val packageName: String)

/**
 * Which installed app somebody meant by what they said.
 *
 * Opening the wrong app is cheap to undo and annoying every time, so this is ordered by how
 * sure each kind of match is rather than by how clever it is:
 *
 * 1. the name as said, ignoring case and spacing - "yt music" finds "YT Music";
 * 2. the package name, because a model that has seen the list will sometimes say
 *    `com.spotify.music` instead of "Spotify";
 * 3. the label starting with what was said - "insta" finds "Instagram". Starting with, not
 *    containing: what people say is the beginning of an app's name rather than a fragment from
 *    its middle, so an app actually called "Mail Pro" wins "mail" over Gmail, which is still
 *    found by its own name. (The case this rule was first credited with - "messages" not
 *    finding Facebook Messenger - is handled by the tie-break below, not by this.)
 * 4. every word of what was said appearing in the label, which is what catches "google maps"
 *    against "Maps" the other way round - said inside label rather than label inside said.
 *
 * Ties are broken by the shortest label, which is almost always the one meant: "Calendar"
 * rather than "Calendar Widget Pro".
 *
 * Null when nothing matches. An assistant that opens its best guess when it did not recognise
 * the name is worse than one that says it does not have that app - the guess is silent and the
 * user has to work out what happened.
 */
fun appNamed(said: String, installed: List<InstalledApp>): InstalledApp? {
  val wanted = said.normalised()
  if (wanted.isEmpty() || installed.isEmpty()) return null

  val byLabel = installed.filter { it.label.normalised() == wanted }
  if (byLabel.isNotEmpty()) return byLabel.shortest()

  val byPackage = installed.filter { it.packageName.lowercase() == said.trim().lowercase() }
  if (byPackage.isNotEmpty()) return byPackage.first()

  val byPrefix = installed.filter { it.label.normalised().startsWith(wanted) }
  if (byPrefix.isNotEmpty()) return byPrefix.shortest()

  val words = said.lowercase().split(NOT_A_WORD).filter { it.length > 1 }
  if (words.isNotEmpty()) {
    val byWords =
      installed.filter { app ->
        val label = app.label.lowercase()
        words.all { label.contains(it) }
      }
    if (byWords.isNotEmpty()) return byWords.shortest()

    // The other way round, for when the app's own name is shorter than what was said:
    // "google maps" against a label of "Maps".
    val inside =
      installed.filter { app ->
        val label = app.label.normalised()
        label.length > 2 && wanted.contains(label)
      }
    if (inside.isNotEmpty()) return inside.longestLabel()
  }

  return null
}

/** Case, spacing and punctuation all removed: "YT Music" and "yt-music" are the same name. */
private fun String.normalised() = lowercase().replace(NOT_A_WORD, "")

private fun List<InstalledApp>.shortest() = minByOrNull { it.label.length }!!

/** The longest label that still fits inside what was said: "Maps" beats "Map". */
private fun List<InstalledApp>.longestLabel() = maxByOrNull { it.label.length }!!

private val NOT_A_WORD = Regex("[^a-z0-9]+")

/**
 * The kinds of app every phone has one of, whatever it happens to be called.
 *
 * "Open gallery" failed on a phone with a gallery, and "open clock" on one with a clock. Matching
 * by name was always going to: the gallery is "Gallery" on one phone, "Samsung Gallery" on
 * another, "Photos" on a third, and "גלריה" on any phone set to Hebrew. Android already knows
 * which app does each of these jobs - it has a standard way to ask for "the gallery" or "the
 * clock" without naming one - and that is how every built-in assistant opens them.
 */
enum class AppRole {
  GALLERY,
  CLOCK,
  CAMERA,
  SETTINGS,
  CALENDAR,
  CALCULATOR,
  CONTACTS,
  MESSAGES,
  EMAIL,
  MAPS,
  BROWSER,
  MUSIC,
  FILES,
  PHONE,
  STORE,
}

/**
 * Which of those somebody meant, or null when they named an app rather than a kind of app.
 *
 * Only the whole request, once the filler is off it: "open the clock app" is the clock, but
 * "open clock widget pro" is somebody's app called that, and must not be hijacked into the
 * system clock because it contains the word.
 */
fun appRoleFor(said: String): AppRole? {
  val words =
    said.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() && it !in ROLE_FILLER }
  if (words.isEmpty()) return null
  return ROLE_NAMES[words.joinToString(" ")]
}

private val ROLE_FILLER = setOf("the", "my", "app", "application", "a", "open", "launch", "start")

private val ROLE_NAMES: Map<String, AppRole> =
  buildMap {
    listOf("gallery", "photos", "photo", "pictures", "pics", "images").forEach { put(it, AppRole.GALLERY) }
    listOf("clock", "alarm", "alarms", "alarm clock", "timer", "stopwatch").forEach { put(it, AppRole.CLOCK) }
    listOf("camera").forEach { put(it, AppRole.CAMERA) }
    listOf("settings", "phone settings", "system settings").forEach { put(it, AppRole.SETTINGS) }
    listOf("calendar").forEach { put(it, AppRole.CALENDAR) }
    listOf("calculator").forEach { put(it, AppRole.CALCULATOR) }
    listOf("contacts", "contact list", "people").forEach { put(it, AppRole.CONTACTS) }
    listOf("messages", "messaging", "sms", "texts", "text messages").forEach { put(it, AppRole.MESSAGES) }
    listOf("email", "mail", "e mail").forEach { put(it, AppRole.EMAIL) }
    listOf("maps", "map", "navigation").forEach { put(it, AppRole.MAPS) }
    listOf("browser", "internet", "web browser").forEach { put(it, AppRole.BROWSER) }
    listOf("music", "music player").forEach { put(it, AppRole.MUSIC) }
    listOf("files", "file manager", "my files", "downloads").forEach { put(it, AppRole.FILES) }
    listOf("phone", "dialer", "dialler", "keypad").forEach { put(it, AppRole.PHONE) }
    listOf("play store", "store", "app store", "galaxy store").forEach { put(it, AppRole.STORE) }
  }

/** Only an exact name, case and spacing aside: the one match safe to prefer over a role. */
fun appNamedExactly(said: String, installed: List<InstalledApp>): InstalledApp? {
  val wanted = said.lowercase().replace(NOT_A_WORD, "")
  if (wanted.isEmpty()) return null
  return installed.filter { it.label.lowercase().replace(NOT_A_WORD, "") == wanted }.minByOrNull { it.label.length }
}

/**
 * The installed apps whose names come closest to what was said, for a failure that can be
 * acted on: "no app called 'galery' - the closest are Gallery, Galaxy Store" rather than a
 * flat no. Edit distance on the names with case and spacing taken out, nearest first.
 */
fun closestApps(said: String, installed: List<InstalledApp>, count: Int = 4): List<String> {
  val wanted = said.lowercase().replace(NOT_A_WORD, "")
  if (wanted.isEmpty()) return emptyList()
  return installed
    .map { it.label to distance(wanted, it.label.lowercase().replace(NOT_A_WORD, "")) }
    .sortedWith(compareBy({ it.second }, { it.first.length }))
    .map { it.first }
    .distinct()
    .take(count)
}

private fun distance(a: String, b: String): Int {
  var previous = IntArray(b.length + 1) { it }
  for (i in 1..a.length) {
    val current = IntArray(b.length + 1)
    current[0] = i
    for (j in 1..b.length) {
      val cost = if (a[i - 1] == b[j - 1]) 0 else 1
      current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
    }
    previous = current
  }
  return previous[b.length]
}
