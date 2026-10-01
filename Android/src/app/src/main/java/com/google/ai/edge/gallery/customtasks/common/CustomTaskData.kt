/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.customtasks.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Data class to hold information passed to the `MainScreen` composable of a custom task.
 *
 * @param modelManagerViewModel The ViewModel providing access to the state of models and their
 *   management.
 * @param bottomPadding The bottom padding of the Scaffold's `innerPadding`. By default, your
 *   `MainScreen` will extend to the bottom edge. Use this value if you need to apply padding to the
 *   bottom of your screen's content to account for elements like a bottom navigation bar.
 * @param setAppBarControlsDisabled A callback function that the custom task screen can call to
 *   enable and disable controls (e.g. back button, configs, etc) in the app bar.
 * @param setTopBarVisible A callback function that the custom task screen can call to show and hide
 *   the top bar.
 */
data class CustomTaskData(
  val modelManagerViewModel: ModelManagerViewModel,
  val bottomPadding: Dp = 0.dp,
  val setAppBarControlsDisabled: (Boolean) -> Unit = {},
  val setTopBarVisible: (Boolean) -> Unit = {},
  val setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit = {},
)

data class CustomTaskDataForBuiltinTask(
  val modelManagerViewModel: ModelManagerViewModel,
  val onNavUp: () -> Unit,
  // The initial query to be sent to the model when the screen is first loaded.
  val initialQuery: String? = null,
  // Content URIs of images to attach to that initial message. Set when the user shares images
  // into the app, or fires the system assist gesture with a screenshot.
  val initialImageUris: List<String> = listOf(),
  // Open the microphone as soon as the screen appears, rather than waiting for the keyboard. Set
  // when the assistant was summoned hands-free, where nobody can reach the screen.
  val startListening: Boolean = false,
  // Opens the capability gallery. Only set for the assistant screen, which replaces the gallery
  // as the app's start destination and therefore has to offer a way back to it.
  val onOpenGallery: (() -> Unit)? = null,
  // Opens the model manager, where a model file of the user's own is imported. Only set for the
  // assistant screen, which is otherwise three screens away from it.
  val onOpenModels: (() -> Unit)? = null,
)
