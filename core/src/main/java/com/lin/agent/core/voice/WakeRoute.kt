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

package com.lin.agent.core.voice

/** What the wake word can do with what it heard. */
enum class WakeRoute {
  /** Straight to the panel, which is the point of a wake word. */
  PANEL,

  /** A notification to tap, for when the panel cannot be opened. */
  NOTIFICATION,
}

/**
 * Whether hearing "Hey Lin" can open the panel, or has to ask to be let in.
 *
 * This is a platform rule and not a choice. An app in the background may not start an activity,
 * and a foreground service is **not** an exception to that - the microphone notification does
 * not buy a window. Worse, a blocked launch is dropped silently rather than refused, so the
 * wake word would hear the question, have nowhere to put it, and look broken with nothing in
 * the logs anybody is reading.
 *
 * Two of the exceptions apply here. Granting "appear over other apps" lets the launch through,
 * which is what makes the wake word hands-free. Failing that, an app with a window already on
 * screen may start another - so with Lin open, this works without any permission at all.
 *
 * With neither, there is still one background start that is always allowed: the user tapping a
 * notification the app posted. That is a tap rather than hands-free, and saying so is better
 * than a wake word that silently does nothing.
 */
fun wakeRoute(canAppearOverOtherApps: Boolean, appHasVisibleWindow: Boolean): WakeRoute =
  if (canAppearOverOtherApps || appHasVisibleWindow) WakeRoute.PANEL else WakeRoute.NOTIFICATION
