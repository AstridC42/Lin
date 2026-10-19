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

package com.lin.agent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device quirks, tested without a device.
 *
 * This is lifted knowledge: nothing here can be derived from a specification, it is what running
 * these models on real hardware taught the app this one replaces. The reason it is worth testing
 * rather than trusting is that the original could only be checked by holding the phone, so a
 * mistake in it was invisible until somebody's app failed to load a model.
 */
class DeviceChoiceTest {

  @Test
  fun gpuIsTheDefaultOnOrdinaryDevices() {
    assertEquals(
      DeviceChoice.Accelerator.GPU,
      DeviceChoice.acceleratorFor(
        DeviceChoice.Accelerator.GPU,
        manufacturer = "samsung",
        model = "SM-F766B",
      ),
    )
  }

  @Test
  fun pixel10FallsBackToCpuBecauseItsGpuPathIsUnusable() {
    for (model in listOf("Pixel 10", "Pixel 10 Pro", "Pixel 10 Pro XL")) {
      assertEquals(
        "GPU should not be used on $model",
        DeviceChoice.Accelerator.CPU,
        DeviceChoice.acceleratorFor(
          DeviceChoice.Accelerator.GPU,
          manufacturer = "Google",
          model = model,
        ),
      )
    }
  }

  /**
   * The prefix trap this is written to avoid.
   *
   * Matching on "Pixel 1" would catch Pixel 10, and matching loosely on "Pixel 10" would catch a
   * hypothetical "Pixel 100". Both would quietly put a working device on the slow path.
   */
  @Test
  fun olderPixelsAreNotCaughtByThePrefix() {
    for (model in listOf("Pixel 1", "Pixel 100", "Pixel 9 Pro", "Pixel 6a")) {
      assertFalse("$model should keep its GPU", DeviceChoice.gpuIsUnreliable("Google", model))
    }
  }

  @Test
  fun theManufacturerMattersAndIsCaseInsensitive() {
    assertTrue(DeviceChoice.gpuIsUnreliable("google", "Pixel 10"))
    assertTrue(DeviceChoice.gpuIsUnreliable("GOOGLE", "Pixel 10"))
    // Another manufacturer's device that happens to be named similarly is not Google's problem.
    assertFalse(DeviceChoice.gpuIsUnreliable("OtherCo", "Pixel 10"))
  }

  /** Vision is GPU or it is off: the encoder has no CPU path in these builds. */
  @Test
  fun visionIsGpuWhereThereIsOneAndAbsentWhereThereIsNot() {
    assertEquals(
      DeviceChoice.Accelerator.GPU,
      DeviceChoice.visionAccelerator(manufacturer = "samsung", model = "SM-F766B"),
    )
    assertNull(
      "vision should be off rather than wrong on a device with no usable GPU",
      DeviceChoice.visionAccelerator(manufacturer = "Google", model = "Pixel 10"),
    )
  }

  /** Audio is CPU, even in an engine whose vision encoder is on the GPU. */
  @Test
  fun audioIsAlwaysCpu() {
    assertEquals(DeviceChoice.Accelerator.CPU, DeviceChoice.audioAccelerator())
  }

  /**
   * GPU is tried, and CPU always follows it.
   *
   * With one accelerator and no fallback, a GPU that would not load was the end of every
   * conversation the app could have. That is too much to lose to a vendor library being absent,
   * a driver refusing, or - as actually happened - the app forgetting to declare the library it
   * wanted to open. A slower reply is the right price for being wrong about an accelerator.
   */
  @Test
  fun gpuAlwaysFallsBackToCpu() {
    assertEquals(
      listOf(DeviceChoice.Accelerator.GPU, DeviceChoice.Accelerator.CPU),
      DeviceChoice.acceleratorChain(
        DeviceChoice.Accelerator.GPU,
        manufacturer = "samsung",
        model = "SM-F966B",
      ),
    )
  }

  /** A device already known to fail on GPU does not waste a load attempt on it. */
  @Test
  fun aKnownBadGpuIsNotEvenTried() {
    assertEquals(
      listOf(DeviceChoice.Accelerator.CPU),
      DeviceChoice.acceleratorChain(
        DeviceChoice.Accelerator.GPU,
        manufacturer = "Google",
        model = "Pixel 10 Pro",
      ),
    )
  }

  /** NPU needs vendor runtime libraries that may not be there, so it falls back too. */
  @Test
  fun npuFallsBackToCpu() {
    assertEquals(
      listOf(DeviceChoice.Accelerator.NPU, DeviceChoice.Accelerator.CPU),
      DeviceChoice.acceleratorChain(
        DeviceChoice.Accelerator.NPU,
        manufacturer = "samsung",
        model = "SM-F966B",
      ),
    )
  }

  /** CPU is the floor. There is nothing under it to fall back to, so the chain is one long. */
  @Test
  fun cpuIsTheEndOfEveryChain() {
    val chain =
      DeviceChoice.acceleratorChain(
        DeviceChoice.Accelerator.CPU,
        manufacturer = "samsung",
        model = "SM-F966B",
      )
    assertEquals(listOf(DeviceChoice.Accelerator.CPU), chain)
  }

  /** Whatever is asked for, the walk ends somewhere every device can run. */
  @Test
  fun everyChainEndsOnCpu() {
    for (requested in DeviceChoice.Accelerator.entries) {
      val chain = DeviceChoice.acceleratorChain(requested, manufacturer = "x", model = "y")
      assertEquals(
        "$requested should end on CPU",
        DeviceChoice.Accelerator.CPU,
        chain.last(),
      )
    }
  }
}
