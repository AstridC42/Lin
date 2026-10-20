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

package com.lin.agent.core.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionTest {
  @Test
  fun hebrewAndArabicReadRightToLeft() {
    assertTrue(isRightToLeft("שלום, מה שלומך?"))
    assertTrue(isRightToLeft("**שלום** עולם"))
    assertTrue(isRightToLeft("3 דברים לעשות היום"))
    assertTrue(isRightToLeft("- רשימה"))
    assertTrue(isRightToLeft("مرحبا"))
  }

  @Test
  fun everythingElseReadsLeftToRight() {
    assertFalse(isRightToLeft("Hello"))
    assertFalse(isRightToLeft("OK, תודה"))
    assertFalse(isRightToLeft("12:30 👍"))
    assertFalse(isRightToLeft(""))
  }
}
