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
import android.util.Log
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

  /** Where an archive model is unpacked: the folder the archive itself holds. */
  fun folderFor(model: CatalogModel): File = File(directory(), model.build.file.removeSuffix(ARCHIVE_SUFFIX))

  /** Whether the file is there and is not obviously a partial download. */
  fun isPresent(model: CatalogModel): Boolean {
    // An archive is ready once it has been unpacked to the end, which is when the mark is written.
    if (model.isArchive) return File(folderFor(model), UNPACKED_MARK).exists()
    val file = fileFor(model)
    if (!file.exists()) return false
    // A file much smaller than advertised is an interrupted download rather than a model, and
    // handing that to the engine produces a native crash rather than an error.
    return file.length() >= model.build.sizeBytes / 2
  }

  /** Models of [role] that could be loaded right now. */
  fun available(role: ModelRole): List<CatalogModel> =
    ModelCatalog.all.filter { it.role == role && isPresent(it) }

  /**
   * Deletes partial downloads nothing will ever finish, and returns the bytes freed.
   *
   * See the rule in [clearStrayPartsIn]; this applies it to the models directory.
   */
  fun clearStrayParts(): Long = clearStrayPartsIn(directory())

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

/**
 * Deletes partial downloads in [directory] that nothing will ever finish, and returns the bytes
 * freed.
 *
 * A `.part` file is only worth keeping if a future download would resume into it, and the file a
 * download resumes into is named after the build this app would fetch. Change which build that is
 * and the old partial becomes unreachable: invisible from inside the app, not counted as a model,
 * and still occupying the storage.
 *
 * That is not hypothetical. Downloads briefly fetched the chip-specific build on phones that had
 * one published, so a phone that tried and failed is left holding up to three gigabytes under a
 * name the app no longer looks at. Nothing in the UI could have said so, and the only way to get
 * it back was to uninstall - which also takes any model that did work.
 *
 * Safe to call while another model is downloading: that download's own `.part` file is one of the
 * wanted ones. Finished model files are never candidates, including an NPU build somebody copied
 * in by hand, because they are not partials.
 *
 * A top-level function rather than a method so it can be tested against a real directory without
 * a Context.
 */
internal fun clearStrayPartsIn(directory: File): Long {
  val wanted = ModelCatalog.all.map { it.build.file + ModelDownload.PART_SUFFIX }.toSet()
  var freed = 0L
  for (file in directory.listFiles() ?: return 0L) {
    if (!file.isFile) continue
    if (!file.name.endsWith(ModelDownload.PART_SUFFIX)) continue
    if (file.name in wanted) continue
    val size = file.length()
    if (file.delete()) {
      Log.i("LinModelStore", "Removed an unreachable partial download: ${file.name} ($size bytes)")
      freed += size
    }
  }
  return freed
}

/** Written last into an unpacked archive's folder: its presence means every file is there. */
const val UNPACKED_MARK = ".unpacked"
