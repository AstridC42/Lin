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

package com.lin.agent.core.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shrinking a photo on the way into a conversation.
 *
 * Not an optimisation: a phone camera produces twelve megapixels, a vision encoder sees a few
 * hundred pixels a side whatever it is handed, and the difference is a base64 string big
 * enough to make the request fail and a bitmap big enough to matter on a phone already holding
 * two and a half gigabytes of weights.
 */
class PicturesTest {

  @Test
  fun aPictureAlreadySmallEnoughIsNotSampled() {
    assertEquals(1, sampleSizeFor(800, 600, longest = 1024))
    assertEquals(1, sampleSizeFor(1024, 768, longest = 1024))
  }

  /**
   * A phone-camera photo comes down a long way, but never below the target.
   *
   * 4000 against 1024 samples by two, to 2000 - not by four, which would leave 1000 and be
   * under what was asked for. Erring large is the right direction: an encoder downsamples
   * what it is given, and cannot invent detail that was thrown away here.
   */
  @Test
  fun aPhoneCameraPhotoComesDownButNotBelowTheTarget() {
    assertEquals(2, sampleSizeFor(4000, 3000, longest = 1024))
    assertTrue(4000 / 2 >= 1024)
  }

  /** The longest side is what matters, whichever way round the picture is. */
  @Test
  fun aPortraitPhotoIsTreatedLikeItsLandscape() {
    assertEquals(sampleSizeFor(4000, 3000, 1024), sampleSizeFor(3000, 4000, 1024))
  }

  /** inSampleSize rounds down to a power of two anyway; anything else is false precision. */
  @Test
  fun theResultIsAlwaysAPowerOfTwo() {
    for (width in listOf(100, 999, 1025, 2000, 5000, 12000)) {
      val sample = sampleSizeFor(width, width, 1024)
      assertTrue("$width gave $sample", sample > 0 && (sample and (sample - 1)) == 0)
    }
  }

  @Test
  fun samplingNeverLeavesItSmallerThanTheTarget() {
    for (width in listOf(1025, 2048, 4000, 12000)) {
      assertTrue(width / sampleSizeFor(width, width, 1024) >= 1024)
    }
  }

  /** A provider that reports no size must not produce a zero or negative divisor. */
  @Test
  fun anUnknownSizeIsLeftAlone() {
    assertEquals(1, sampleSizeFor(0, 0, 1024))
    assertEquals(1, sampleSizeFor(-1, -1, 1024))
    assertEquals(1, sampleSizeFor(4000, 3000, longest = 0))
  }
}
