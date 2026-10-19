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

package com.lin.agent.core.privileged

/**
 * Whether Lin has borrowed the ADB shell's privileges, and if not, why not.
 *
 * Shizuku is a separate app the user installs. It starts a service under the `shell` user over
 * Wireless Debugging - the same identity `adb shell` has, and no root - and lends it to apps
 * that ask. That is what turns "Lin can open the Wi-Fi panel for you" into "Lin turned Wi-Fi
 * off", because `svc wifi disable` has been a shell command since long before Android took the
 * API away from apps.
 *
 * Four ways of not having it, kept apart because the thing to do about each is different and
 * from the outside they all look identical - a switch that did not move. "Install Shizuku",
 * "start Shizuku", "allow Lin in Shizuku" and "you told Shizuku never to ask again" are four
 * different sentences, and collapsing them into "privileged access unavailable" is how an
 * assistant becomes useless to somebody who would happily have fixed it.
 */
enum class ShizukuState {
  /** Shizuku is not on the phone. */
  NOT_INSTALLED,

  /** Installed, but its service is not running - the usual case after a reboot. */
  NOT_RUNNING,

  /** Running, and Lin has not been allowed yet. Asking is worth doing. */
  NOT_PERMITTED,

  /** Running, and the user chose "deny and don't ask again". Asking again does nothing. */
  REFUSED,

  /** Lin can run privileged commands. */
  READY,
}

/**
 * Which of those five it is.
 *
 * The order is the whole content of this function, and it is not the order the flags come in.
 * A phone where Shizuku was uninstalled still has a stale permission grant recorded, so
 * [permitted] can be true while there is nothing to be permitted by; and Shizuku's own
 * "should show rationale" flag - [refused] here - stays true after the service dies. Checking
 * the cheap flags first would report REFUSED on a phone that simply needs Shizuku started, and
 * send the user to a dialog that is not the problem.
 *
 * So: is it there, is it running, and only then, what has it been told about Lin.
 */
fun shizukuState(
  installed: Boolean,
  running: Boolean,
  permitted: Boolean,
  refused: Boolean = false,
): ShizukuState =
  when {
    !installed -> ShizukuState.NOT_INSTALLED
    !running -> ShizukuState.NOT_RUNNING
    permitted -> ShizukuState.READY
    refused -> ShizukuState.REFUSED
    else -> ShizukuState.NOT_PERMITTED
  }

/** Whether privileged commands can be run right now. */
fun ShizukuState.isReady() = this == ShizukuState.READY

/**
 * What to tell the user, in the words of the thing they have to do.
 *
 * Written for a tool result rather than for a settings screen: this text reaches a model that
 * is about to say it out loud, so it names the app, the step and nothing else. The one thing it
 * must not do is apologise about an API, because the user cannot act on that.
 */
fun whatToDoAbout(state: ShizukuState): String =
  when (state) {
    ShizukuState.READY -> ""
    ShizukuState.NOT_INSTALLED ->
      "Shizuku is not installed. It is a separate free app that lends Lin the same privileges " +
        "the USB debugging shell has, which is what lets Lin really change these settings " +
        "rather than opening the panel. Without it Lin can only open the panel."
    ShizukuState.NOT_RUNNING ->
      "Shizuku is installed but not running. It has to be started again after every restart of " +
        "the phone: open Shizuku and pair it through Wireless debugging."
    ShizukuState.NOT_PERMITTED ->
      "Shizuku is running but has not been asked about Lin yet. Lin can request it; Shizuku " +
        "shows the prompt."
    ShizukuState.REFUSED ->
      "Shizuku was told not to ask about Lin again, so Lin cannot request it. It has to be " +
        "allowed from inside Shizuku, under the list of apps."
  }
