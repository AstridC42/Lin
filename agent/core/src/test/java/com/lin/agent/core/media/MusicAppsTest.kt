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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The second way in, for the apps whose play-from-search intent does nothing. */
class MusicAppsTest {

  @Test
  fun `a search link for the apps people use`() {
    assertEquals(
      "https://tidal.com/search?q=Stan+Eminem",
      searchUrlFor("com.aspiro.tidal", "Stan Eminem"),
    )
    assertEquals(
      "https://music.youtube.com/search?q=Stan+Eminem",
      searchUrlFor("com.google.android.apps.youtube.music", "Stan Eminem"),
    )
  }

  @Test
  fun `a query with characters a URL cannot hold`() {
    // "Rock & Roll" unencoded ends the query at the ampersand and searches for "Rock".
    val url = searchUrlFor("com.spotify.music", "Rock & Roll")!!
    assertTrue(url, url.endsWith("Rock+%26+Roll"))
  }

  @Test
  fun `an app with no known link says so instead of guessing one`() {
    // A guessed URL opens a browser rather than the music app, which is worse than the
    // caller falling back to opening the app itself.
    assertNull(searchUrlFor("com.example.player", "Stan"))
  }

  @Test
  fun `nothing to search for`() {
    assertNull(searchUrlFor("com.aspiro.tidal", "   "))
  }
}
