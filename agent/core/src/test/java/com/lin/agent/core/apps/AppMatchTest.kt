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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finding the app somebody named, and admitting it when there isn't one. */
class AppMatchTest {

  private val phone =
    listOf(
      InstalledApp("Messages", "com.google.android.apps.messaging"),
      InstalledApp("Facebook Messenger", "com.facebook.orca"),
      InstalledApp("YT Music", "com.google.android.apps.youtube.music"),
      InstalledApp("TIDAL", "com.aspiro.tidal"),
      InstalledApp("Maps", "com.google.android.apps.maps"),
      InstalledApp("Instagram", "com.instagram.android"),
      InstalledApp("Calendar Widget Pro", "com.widgets.calendar"),
      InstalledApp("Calendar", "com.google.android.calendar"),
    )

  @Test
  fun `the name as said`() {
    assertEquals("com.aspiro.tidal", appNamed("tidal", phone)?.packageName)
    assertEquals("com.aspiro.tidal", appNamed("TIDAL", phone)?.packageName)
  }

  @Test
  fun `spacing and punctuation do not matter`() {
    assertEquals("com.google.android.apps.youtube.music", appNamed("yt-music", phone)?.packageName)
    assertEquals("com.google.android.apps.youtube.music", appNamed("ytmusic", phone)?.packageName)
  }

  @Test
  fun `a package name, because a model will sometimes say one`() {
    assertEquals("TIDAL", appNamed("com.aspiro.tidal", phone)?.label)
  }

  @Test
  fun `half a name finds the app it starts`() {
    assertEquals("Instagram", appNamed("insta", phone)?.label)
  }

  @Test
  fun `the shorter name wins a tie`() {
    // "Calendar" rather than "Calendar Widget Pro", which is what somebody asking for their
    // calendar means every time.
    assertEquals("com.google.android.calendar", appNamed("calendar", phone)?.packageName)
  }

  @Test
  fun `the app whose name starts that way wins over one that merely contains it`() {
    // Gmail is shorter and contains "mail", so the shortest-label tie-break would take it if
    // containing were tried first. What somebody says is the start of a name, not a fragment
    // from the middle of one - and Gmail is still found by saying "gmail".
    val withMail = phone + InstalledApp("Gmail", "com.google.android.gm") +
      InstalledApp("Mail Pro", "com.example.mail")
    assertEquals("Mail Pro", appNamed("mail", withMail)?.label)
    assertEquals("Gmail", appNamed("gmail", withMail)?.label)
  }

  @Test
  fun `the app, not the one that has its name inside a longer one`() {
    assertEquals("Messages", appNamed("messages", phone)?.label)
    assertEquals("Facebook Messenger", appNamed("messenger", phone)?.label)
  }

  @Test
  fun `a longer phrase finds the shorter app`() {
    // People say "google maps"; the app is called "Maps".
    assertEquals("Maps", appNamed("google maps", phone)?.label)
  }

  @Test
  fun `an app that is not there is said rather than guessed`() {
    assertNull(appNamed("spotify", phone))
    assertNull(appNamed("", phone))
    assertNull(appNamed("tidal", emptyList()))
  }
}
