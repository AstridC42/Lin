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

/**
 * Getting to the screen where a phone's assistant is chosen.
 *
 * Lin cannot make itself the assistant. The role is exclusive, it is not one an app may ask
 * for, and the request intent for it is the worst of both worlds - on the phones where it is
 * refused it is refused silently, so a button using it would appear to do nothing. The user
 * sets it themselves, and all Lin can honestly do is take them to the right screen and say
 * plainly whether it worked.
 *
 * Which screen that is varies by manufacturer and by version, so this is a list in order of
 * preference rather than one action, and the only guarantee is the last one: the top of
 * Settings exists on every phone.
 */
object AssistantRole {

  /**
   * Screens that can set the assistant, best first.
   *
   * The assistant screen itself; then the default-apps list, which contains it on versions
   * where the first is missing; then Settings, which is not where anybody wants to be dropped
   * but is better than a button that throws.
   */
  val settingsScreens: List<String> =
    listOf(
      "android.settings.VOICE_INPUT_SETTINGS",
      "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
      "android.settings.SETTINGS",
    )

  /**
   * The first of [candidates] this phone can actually open.
   *
   * The reason this is a function and not a hard-coded action: starting an intent nothing
   * handles throws, and it throws inside the click handler of a settings button - so the one
   * failure mode here is a crash on the screen somebody went to for help. Asking first is the
   * difference.
   *
   * Null when the phone handles none of them, which should not happen while [settingsScreens]
   * ends where it does, but is returned rather than assumed away: a caller that has to decide
   * what to do about it will write something better than a thrown exception.
   */
  fun firstResolvable(
    candidates: List<String> = settingsScreens,
    canResolve: (String) -> Boolean,
  ): String? = candidates.firstOrNull(canResolve)
}
