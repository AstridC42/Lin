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

import android.os.Build

/**
 * Which accelerator to ask for, and the device-specific exceptions to that.
 *
 * Lifted knowledge rather than design. None of this can be derived from a specification: it is
 * what the Edge Gallery accumulated from running these models on real hardware, and throwing it
 * away in the name of a clean rewrite would mean rediscovering it one broken device at a time.
 *
 * Kept as data and pure functions so it can be tested without a phone, which is the one thing the
 * original could not do.
 */
object DeviceChoice {

  /** How a model's weights are executed. */
  enum class Accelerator {
    CPU,
    GPU,
    NPU,
  }

  /**
   * The accelerator to use for [requested] on this device.
   *
   * GPU is the right default for these models, with one known exception carried over: Pixel 10
   * removes GPU from its advertised accelerators, so asking for it there is asking for a failure.
   */
  fun acceleratorFor(
    requested: Accelerator,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
  ): Accelerator =
    if (requested == Accelerator.GPU && gpuIsUnreliable(manufacturer, model)) Accelerator.CPU
    else requested

  /**
   * The accelerators to try for [requested], in order, until one loads.
   *
   * A list rather than a single answer. The original idea was that the devices whose GPU path
   * does not work could be named - [gpuIsUnreliable] is that list - and a list of names is the
   * wrong shape for this: whether an accelerator is usable depends on the vendor libraries
   * actually present on the device, which is not something a model name predicts.
   *
   * Worth recording what this was first blamed on, because the wrong lesson is easy to draw. A
   * Galaxy Z Fold 7 failed every GPU load with "Can not find OpenCL library on this device", and
   * that was read as the device lacking OpenCL. It does not: the same phone runs these models on
   * the GPU in Google's own Gallery app. The app had simply not declared `libOpenCL.so` in its
   * manifest, and since Android 12 an undeclared vendor library cannot be opened. The
   * declaration is the fix; this chain is the safety net, and it should not be mistaken for one.
   *
   * It still earns its place. A vendor library really can be absent, a driver really can refuse,
   * and the cost of being wrong about it should be a slower reply rather than an app that cannot
   * answer at all. CPU needs no vendor anything and is the floor every device has, so it goes
   * last on every chain.
   */
  fun acceleratorChain(
    requested: Accelerator,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
  ): List<Accelerator> =
    when (acceleratorFor(requested, manufacturer, model)) {
      Accelerator.CPU -> listOf(Accelerator.CPU)
      Accelerator.GPU -> listOf(Accelerator.GPU, Accelerator.CPU)
      Accelerator.NPU -> listOf(Accelerator.NPU, Accelerator.CPU)
    }

  /**
   * Devices whose GPU path is known not to work for these models.
   *
   * Retained because skipping a load that is known to fail is still worth a device name, but it
   * is no longer what keeps the app working - [acceleratorChain] is. See the note there.
   */
  fun gpuIsUnreliable(manufacturer: String, model: String): Boolean {
    val isGoogle = manufacturer.lowercase() == "google"
    // "Pixel 10", "Pixel 10 Pro", "Pixel 10 Pro XL" - and deliberately not "Pixel 1", which a
    // prefix match on "Pixel 1" would catch.
    return isGoogle && Regex("^Pixel 10(\\s|$)").containsMatchIn(model)
  }

  /**
   * Which accelerator handles images, when the model understands them.
   *
   * GPU, and not negotiable for Gemma 3n and 4: the vision encoder does not run on CPU in these
   * builds. If the GPU is unusable on a device, vision is off rather than wrong.
   */
  fun visionAccelerator(manufacturer: String = Build.MANUFACTURER, model: String = Build.MODEL):
    Accelerator? = if (gpuIsUnreliable(manufacturer, model)) null else Accelerator.GPU

  /**
   * Which accelerator handles audio.
   *
   * CPU, and also not negotiable for these models - the audio encoder is a CPU path. Pairing it
   * with a GPU vision encoder in one engine looks odd and is correct.
   */
  fun audioAccelerator(): Accelerator = Accelerator.CPU
}
