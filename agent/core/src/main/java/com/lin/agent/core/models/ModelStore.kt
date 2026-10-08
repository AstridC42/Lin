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

package com.lin.agent.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Where model files live on the device, and how much memory there is to run them in.
 *
 * A file copied in by hand counts, and is still worth knowing about: it is the one way to avoid
 * spending two and a half gigabytes of somebody's allowance on a model they already have.
 */
class ModelStore(private val context: Context) {

  /** Where a model file belongs. */
  fun fileFor(model: CatalogModel): File = File(directory(), model.build.file)

  /** Whether the file is there and is not obviously a partial download. */
  fun isPresent(model: CatalogModel): Boolean {
    val file = fileFor(model)
    if (!file.exists()) return false
    // A file much smaller than advertised is an interrupted download rather than a model, and
    // handing that to the engine produces a native crash rather than an error.
    return file.length() >= model.build.sizeBytes / 2
  }

  /** Models of [role] that could be loaded right now. */
  fun available(role: ModelRole): List<CatalogModel> =
    ModelCatalog.all.filter { it.role == role && isPresent(it) }

  fun directory(): File =
    File(context.filesDir, "models").also { if (!it.exists()) it.mkdirs() }

  /** Somewhere the engine may keep its own cache, kept apart from the weights. */
  fun cacheDirectory(): File =
    File(context.cacheDir, "engine").also { if (!it.exists()) it.mkdirs() }

  /**
   * Device memory in whole gigabytes, as Android reports it.
   *
   * `advertisedMem` from API 34, which is the figure that matches what the phone is sold as;
   * `totalMem` reports what is left after the kernel and reserved regions, so it reads a 12 GB
   * phone as about 11 and would reject a model the device can actually hold.
   */
  fun memoryGb(): Int {
    val manager =
      context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    val bytes =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.advertisedMem
      else info.totalMem
    return (bytes / BYTES_IN_GB).toInt()
  }

  private companion object {
    const val BYTES_IN_GB = 1024L * 1024 * 1024
  }
}
