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

package com.lin.agent.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.lin.agent.core.fold.Posture
import com.lin.agent.core.fold.postureOf

/**
 * How the phone is folded, as a value a layout can switch on.
 *
 * Jetpack's own, not Samsung's: the hinge is reported through `WindowInfoTracker` on every
 * foldable, so a Flex mode layout written against this works on a Galaxy and on whatever else
 * folds. Samsung's SDK would have tied it to one manufacturer for the same answer.
 *
 * [Posture.FLAT] whenever the phone is not half open, including on every device that does not
 * fold at all - so a caller that does nothing special for the other two is correct everywhere
 * by default.
 */
@Composable
fun rememberPosture(): State<Posture> {
  val context = LocalContext.current
  val activity = context as? Activity

  return produceState(initialValue = Posture.FLAT, activity) {
    if (activity == null) return@produceState
    runCatching {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layout ->
          val fold = layout.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
          value =
            postureOf(
              halfOpened = fold?.state == FoldingFeature.State.HALF_OPENED,
              hingeIsHorizontal = fold?.orientation == FoldingFeature.Orientation.HORIZONTAL,
            )
        }
      }
      // A device that reports nothing, or a tracker that will not start, is a flat phone.
      .onFailure { value = Posture.FLAT }
  }
}

/** Unused outside previews; kept so a preview can pin a posture without a hinge. */
@Composable fun fixedPosture(posture: Posture): State<Posture> = mutableStateOf(posture)
