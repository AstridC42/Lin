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

package com.lin.agent.core.history

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {

  private val zone = ZoneId.of("Asia/Jerusalem")
  private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

  @Test
  fun `a conversation is called by its first question`() {
    val lines = listOf(SavedLine(false, "Hello"), SavedLine(true, "What are the specs of the Orin Nano?"), SavedLine(true, "later"))
    assertEquals("What are the specs of the Orin Nano?", chatTitle(lines))
  }

  @Test
  fun `a long first question is cut at a word`() {
    val title = chatTitle(listOf(SavedLine(true, "search the web and tell me everything about the Jetson Orin Nano Super developer kit")))
    assertEquals("search the web and tell me everything about the…", title)
  }

  @Test
  fun `no question yet is a new conversation`() {
    assertEquals("New conversation", chatTitle(emptyList()))
  }

  @Test
  fun `saving again replaces, most recent first, capped`() {
    val a = SavedChat("a", "A", 1, listOf(SavedLine(true, "x")))
    val b = SavedChat("b", "B", 2, listOf(SavedLine(true, "y")))
    val list = withChat(withChat(emptyList(), a), b)
    assertEquals(listOf("b", "a"), list.map { it.id })
    val again = withChat(list, a.copy(updatedAt = 3))
    assertEquals(listOf("a", "b"), again.map { it.id })
    assertEquals(1, withChat(again, b.copy(updatedAt = 4), keep = 1).size)
  }

  @Test
  fun `search finds every word in title or text`() {
    val chats =
      listOf(
        SavedChat("a", "Orin Nano specs", 1, listOf(SavedLine(true, "specs please"), SavedLine(false, "67 TOPS"))),
        SavedChat("b", "Dinner ideas", 2, listOf(SavedLine(true, "pasta?"))),
      )
    assertEquals(listOf("a"), searchChats(chats, "orin TOPS").map { it.id })
    assertEquals(2, searchChats(chats, "  ").size)
    assertEquals(0, searchChats(chats, "orin pasta").size)
  }

  @Test
  fun `conversations are grouped by the day they were last touched`() {
    val now = at(19, 12)
    assertEquals(When.TODAY, whenHeading(at(19, 1), now, zone))
    assertEquals(When.YESTERDAY, whenHeading(at(18, 23), now, zone))
    assertEquals(When.THIS_WEEK, whenHeading(at(14, 9), now, zone))
    assertEquals(When.OLDER, whenHeading(at(10, 9), now, zone))
  }

  @Test
  fun `history survives a round trip, and a damaged row does not take the rest`() {
    val chats = listOf(SavedChat("a", "A", 5, listOf(SavedLine(true, "hi"), SavedLine(false, "hello"))))
    assertEquals(chats, chatsFrom(storedChats(chats)))
    val damaged = """[{"id":"","lines":[]},{"id":"b","title":"B","at":2,"lines":[{"u":true,"t":"q"}]},"junk"]"""
    assertEquals(listOf("b"), chatsFrom(damaged).map { it.id })
    assertEquals(emptyList<SavedChat>(), chatsFrom("{not json"))
  }

  @Test
  fun `the model is named the way a person would say it`() {
    assertEquals("gemma-4-12B · server", shortModelName("hf.co/unsloth/gemma-4-12B-it-qat-GGUF:UD-Q4_K_XL · server"))
    // An Ollama tag is the size, and stays.
    assertEquals("qwen3:32b · server", shortModelName("qwen3:32b · server"))
    assertEquals("Gemma 4 E2B", shortModelName("Gemma 4 E2B"))
  }
}
