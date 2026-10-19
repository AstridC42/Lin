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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutOutTest {

  private val red = 0xFFFF0000.toInt()
  private val blue = 0xFF0000FF.toInt()

  @Test
  fun `confidence becomes soft edges, fully in and fully out at the ends`() {
    val alpha = CutOut.alphaFrom(floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f))
    assertEquals(0, alpha[0])
    assertEquals(0, alpha[1])
    assertEquals(128, alpha[2])
    assertEquals(255, alpha[3])
    assertEquals(255, alpha[4])
  }

  @Test
  fun `the cut-out keeps the object's colour and makes the rest transparent`() {
    val cut = CutOut.cutOut(intArrayOf(red, blue), intArrayOf(255, 0))
    assertEquals(red, cut[0])
    assertEquals(0, cut[1] ushr 24)
    assertEquals(0x0000FF, cut[1] and 0xFFFFFF)
  }

  @Test
  fun `bounds wrap the object with padding, clamped to the photo`() {
    // A 6x4 photo with the object at x 2..3, y 1..2.
    val alpha = IntArray(24)
    for (y in 1..2) for (x in 2..3) alpha[y * 6 + x] = 255
    assertEquals(CutOut.Box(1, 0, 5, 4), CutOut.bounds(alpha, 6, 4, padding = 1))
    assertEquals(CutOut.Box(2, 1, 4, 3), CutOut.bounds(alpha, 6, 4, padding = 0))
    assertNull(CutOut.bounds(IntArray(24), 6, 4))
  }

  @Test
  fun `crop takes exactly the box`() {
    val pixels = IntArray(12) { it } // 4x3
    assertArrayEquals(intArrayOf(5, 6, 9, 10), CutOut.crop(pixels, 4, CutOut.Box(1, 1, 3, 3)))
  }

  @Test
  fun `blur spreads a single bright pixel and leaves a flat image flat`() {
    val flat = IntArray(25) { blue }
    assertArrayEquals(flat, CutOut.blur(flat, 5, 5, 2))
    val dot = IntArray(25) { 0xFF000000.toInt() }.also { it[12] = 0xFFFFFFFF.toInt() }
    val blurred = CutOut.blur(dot, 5, 5, 1)
    val centre = blurred[12] and 0xFF
    val neighbour = blurred[11] and 0xFF
    assertTrue("centre $centre", centre in 1..254)
    assertTrue("neighbour $neighbour", neighbour > 0)
  }

  @Test
  fun `blend keeps the object sharp and mixes only along the edge`() {
    val out = CutOut.blend(intArrayOf(red, red, red), intArrayOf(blue, blue, blue), intArrayOf(255, 0, 128))
    assertEquals(red, out[0])
    assertEquals(blue, out[1])
    assertEquals(128, (out[2] shr 16) and 0xFF)
    assertEquals(127, out[2] and 0xFF)
  }

  @Test
  fun `an empty selection is noticed`() {
    assertFalse(CutOut.anythingSelected(IntArray(100)))
    assertTrue(CutOut.anythingSelected(IntArray(100) { if (it < 20) 255 else 0 }))
  }

  @Test
  fun `flattening puts the object on white`() {
    val flat = CutOut.onPlain(intArrayOf(red, 0x000000FF))
    assertEquals(red, flat[0])
    assertEquals(0xFFFFFFFF.toInt(), flat[1])
  }
}
