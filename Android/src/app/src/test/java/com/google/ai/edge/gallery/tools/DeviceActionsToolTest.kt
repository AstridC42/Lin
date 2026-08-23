/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionsToolTest {

  @Test
  fun looksLikePhoneNumber_acceptsTheFormsPeopleActuallyType() {
    for (number in
      listOf(
        "+441632960961",
        "+1 (555) 010-4567",
        "0207 946 0958",
        "555-0100",
        "(555) 010-4567",
      )) {
      assertTrue(number, DeviceActionsTool.looksLikePhoneNumber(number))
    }
  }

  @Test
  fun looksLikePhoneNumber_rejectsNamesSoTheyGoThroughContactLookup() {
    for (name in listOf("Sam", "mum", "Dr. Chen", "Sam Smith", "", "  ")) {
      assertFalse(name, DeviceActionsTool.looksLikePhoneNumber(name))
    }
  }

  @Test
  fun looksLikePhoneNumber_rejectsSomethingTooShortToBeANumber() {
    assertFalse(DeviceActionsTool.looksLikePhoneNumber("911"))
    assertFalse(DeviceActionsTool.looksLikePhoneNumber("1"))
  }
}
