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

package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of a web image is actually decoded.
 *
 * A picture found by a search is whatever size the site published. Decoded whole, a five-thousand
 * pixel photograph is about seventy megabytes of ARGB_8888, asked for on a phone already holding
 * a multi-gigabyte model - and the failure is `OutOfMemoryError`, which is an `Error` rather than
 * an `Exception` and so sailed straight through the catch meant to contain it. The app went down,
 * with nothing on screen connecting it to the picture that caused it.
 *
 * BitmapFactory only honours powers of two and rounds down, which is the part that is easy to get
 * subtly wrong: a sample size one step too small still decodes an image larger than the cap.
 */
class ImageDecodeTest {

  @Test
  fun leavesSomethingAlreadySmallEnoughAlone() {
    assertEquals(1, sampleSizeFor(800, 600, 2048))
    assertEquals(1, sampleSizeFor(2048, 2048, 2048))
  }

  @Test
  fun halvesAnImageJustOverTheLimit() {
    assertEquals(2, sampleSizeFor(4096, 3000, 2048))
  }

  /** A photograph off a stock site, which is the case that was taking the app down. */
  @Test
  fun bringsALargePhotographWithinBudget() {
    val sample = sampleSizeFor(6000, 4000, 2048)
    assertTrue("must be a power of two, was $sample", sample and (sample - 1) == 0)
    assertTrue("6000 / $sample must be under the cap", 6000 / sample < 2048)
  }

  /** Whatever the shape, both edges must end up inside the cap. */
  @Test
  fun capsBothEdgesForAnyAspectRatio() {
    for ((w, h) in listOf(9000 to 500, 500 to 9000, 12000 to 12000, 3000 to 100)) {
      val sample = sampleSizeFor(w, h, 2048)
      assertTrue("$w x $h sampled by $sample still too wide", w / sample < 2048)
      assertTrue("$w x $h sampled by $sample still too tall", h / sample < 2048)
    }
  }

  /** Bounds come back as -1 when the bytes are not an image; that must not divide by zero. */
  @Test
  fun survivesBoundsItCouldNotRead() {
    assertEquals(1, sampleSizeFor(-1, -1, 2048))
    assertEquals(1, sampleSizeFor(0, 0, 2048))
    assertEquals(1, sampleSizeFor(4000, 3000, 0))
  }
}
