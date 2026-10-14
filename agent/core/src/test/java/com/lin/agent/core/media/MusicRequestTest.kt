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
import org.junit.Test

/** Turning "play Stan by Eminem" into something a music app's search can use. */
class MusicRequestTest {

  @Test
  fun `a song and an artist`() {
    val request = musicRequest("play Stan by Eminem")!!
    assertEquals("Stan", request.title)
    assertEquals("Eminem", request.artist)
    // Both halves in the query: searching the title alone finds every cover of it.
    assertEquals("Stan Eminem", request.query)
  }

  @Test
  fun `the verb comes off`() {
    // Left in, the search is for a song called "Play", which several exist.
    assertEquals("Stan Eminem", musicRequest("Play Stan by Eminem")?.query)
    assertEquals("Nevermind", musicRequest("put on Nevermind")?.query)
    assertEquals("Radiohead", musicRequest("listen to Radiohead")?.query)
  }

  @Test
  fun `an artist on their own is just a query`() {
    val request = musicRequest("play Eminem")!!
    assertEquals("Eminem", request.query)
    assertEquals("", request.title)
    assertEquals("", request.artist)
  }

  @Test
  fun `the app name at the end is not part of the song`() {
    val request = musicRequest("play Stan by Eminem on Tidal")!!
    assertEquals("Eminem", request.artist)
    assertEquals("Stan Eminem", request.query)
  }

  @Test
  fun `a title that happens to contain a preposition survives`() {
    // The suffix rule only fires on an app name, so these stay whole.
    assertEquals("Knockin on Heavens Door", musicRequest("play Knockin on Heavens Door")?.query)
    assertEquals("In Flames", musicRequest("play In Flames")?.query)
  }

  @Test
  fun `the last by wins`() {
    // "Stand by Me by Ben E King" - the first "by" is part of the title.
    val request = musicRequest("play Stand by Me by Ben E King")!!
    assertEquals("Stand by Me", request.title)
    assertEquals("Ben E King", request.artist)
  }

  @Test
  fun `a dangling by is not a split`() {
    assertEquals("Stan by", musicRequest("play Stan by")?.query)
  }

  @Test
  fun `nothing to play`() {
    assertNull(musicRequest(""))
    assertNull(musicRequest("play"))
    assertNull(musicRequest("   "))
  }
}
