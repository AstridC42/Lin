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
