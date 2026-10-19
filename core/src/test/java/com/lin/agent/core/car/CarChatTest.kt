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

package com.lin.agent.core.car

import com.lin.agent.core.history.SavedChat
import com.lin.agent.core.history.SavedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarChatTest {

  @Test
  fun `the car hears the answer, not its citations, sources or markdown`() {
    val answer =
      """
      **Gemma 4** has a 128K context [1] and runs on phones [2, 3].
      See [the model card](https://ai.google.dev/gemma) or https://huggingface.co/google.

      Sources:
      1. https://ai.google.dev/gemma
      2. https://huggingface.co/google
      """
        .trimIndent()
    val said = forTheCar(answer)
    assertEquals("Gemma 4 has a 128K context and runs on phones. See the model card or.", said)
    assertFalse(said.contains("http"))
    assertFalse(said.contains("["))
    assertFalse(said.contains("*"))
  }

  @Test
  fun `a long answer stops at a sentence`() {
    val answer = "First sentence here. " + "word ".repeat(30) + "end. " + "more ".repeat(200)
    val said = forTheCar(answer, longest = 200)
    assertTrue(said.length <= 200)
    assertTrue(said, said.endsWith("end."))
  }

  @Test
  fun `a long answer with no sentence break is cut at a word`() {
    val said = forTheCar("word ".repeat(100), longest = 50)
    assertTrue(said, said.endsWith("word…"))
    assertTrue(said.length <= 51)
  }

  @Test
  fun `a reply continues the car conversation from the last half hour, and only that`() {
    val now = 10_000_000_000L
    val line = listOf(SavedLine(true, "hi"))
    val recentCar = SavedChat("car-1", "hi", now - 5 * 60_000, line)
    val olderCar = SavedChat("car-0", "hi", now - 10 * 60_000, line)
    val phone = SavedChat("phone", "hi", now - 60_000, line)
    assertEquals("car-1", carChatToContinue(listOf(olderCar, phone, recentCar), now)?.id)
    // The phone's own conversation is never taken over by the car.
    assertNull(carChatToContinue(listOf(phone), now))
    // An hour away starts afresh.
    assertNull(carChatToContinue(listOf(recentCar.copy(updatedAt = now - 60 * 60_000)), now))
  }
}
