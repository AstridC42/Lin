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

import com.lin.agent.core.phone.Switch

/** One command to try, with the words for what it does if it is the one that works. */
data class ShellCommand(val argv: List<String>)

/**
 * The commands that set a switch, best first.
 *
 * Shell tools rather than hidden system interfaces, and that is the whole design decision here.
 * The other way to use Shizuku is to get the Binder for `IWifiManager` and call
 * `setWifiEnabled` on it through a wrapper - which works, and breaks on the next Android
 * version that changes the signature, silently, on somebody's phone rather than in a build.
 * `svc wifi enable` has meant the same thing since Android 4 and is the thing a person would
 * type themselves.
 *
 * A **list** rather than one command, because which of these a given phone and Android version
 * actually has is not knowable from here. The caller runs them in order and stops at the first
 * that works - see [shellWorked], which is the part that is easy to get wrong.
 *
 * Empty for the two switches Lin can already set properly: the torch needs no permission and
 * never did, and Do Not Disturb has a real API behind a grant the user gives once. Running a
 * shell command for either would be borrowing a privilege to do something already allowed.
 */
fun commandsFor(switch: Switch, on: Boolean): List<ShellCommand> {
  val state = if (on) "enable" else "disable"
  return when (switch) {
    Switch.WIFI ->
      listOf(
        ShellCommand(listOf("svc", "wifi", state)),
        // The newer form, for a build whose svc has lost the subcommand. `cmd -w` waits for
        // the service rather than failing when it is not up yet.
        ShellCommand(listOf("cmd", "-w", "wifi", "set-wifi-enabled", if (on) "enabled" else "disabled")),
      )
    Switch.BLUETOOTH ->
      listOf(
        ShellCommand(listOf("svc", "bluetooth", state)),
        ShellCommand(listOf("cmd", "bluetooth_manager", state)),
      )
    Switch.NFC -> listOf(ShellCommand(listOf("svc", "nfc", state)))
    Switch.AIRPLANE_MODE ->
      listOf(
        ShellCommand(listOf("cmd", "connectivity", "airplane-mode", state)),
        // The old two-step: the setting, then the broadcast that makes the radios follow it.
        // Written as one `sh -c` because the second is worthless without the first - a
        // settings row changed with nothing listening is a phone that shows aeroplane mode
        // on with the radios still up.
        ShellCommand(
          listOf(
            "sh",
            "-c",
            "settings put global airplane_mode_on ${if (on) 1 else 0} && " +
              "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on",
          )
        ),
      )
    Switch.FLASHLIGHT, Switch.DO_NOT_DISTURB -> emptyList()
  }
}

/**
 * Whether a command actually did the thing.
 *
 * Not simply `exitCode == 0`, and this is the trap the whole function exists for: `svc` prints
 * its usage and **exits zero** when handed a subcommand it does not have. So a phone whose
 * `svc` has no `nfc` reports success at having printed a help message, and Lin says "done" to
 * nothing - which is the exact failure the honest-results rule in this app is about.
 *
 * So the output is read as well. Anything that looks like a usage message or a refusal counts
 * as not having worked, and the caller moves on to the next command in the list.
 */
fun shellWorked(exitCode: Int, output: String): Boolean {
  if (exitCode != 0) return false
  val said = output.lowercase()
  return COMPLAINTS.none { said.contains(it) }
}

/**
 * The things a shell says when it has not done what it was asked.
 *
 * "available commands" and "usage" are svc and cmd printing help. "unknown command" and
 * "inaccessible or not found" are the shell itself. "permission denial" and "securityexception"
 * are the service refusing, which is what a half-granted Shizuku looks like. "can't find
 * service" is a service that has not started yet.
 */
private val COMPLAINTS =
  listOf(
    "available commands",
    "usage:",
    "unknown command",
    "inaccessible or not found",
    "permission denial",
    "securityexception",
    "can't find service",
    "cannot find service",
    "no such file or directory",
    "error:",
    "exception",
  )

/** What came back from running one command. */
data class ShellOutcome(val exitCode: Int, val output: String)

/**
 * A run encoded as one string, because that is what crosses a Binder cheaply.
 *
 * The privileged half of this lives in another process - Shizuku starts it under the shell
 * user - and an AIDL method returns one value. A Parcelable for two fields would be a file of
 * ceremony for a number and some text, so they travel as `<exit>\n<output>` and are taken
 * apart here, where it can be tested.
 */
fun encodeShellOutcome(exitCode: Int, output: String): String = "$exitCode\n$output"

/**
 * [encoded] taken apart, or a failure.
 *
 * Anything that is not a run is a failure rather than an exception: a dead binder returns
 * null, a service that crashed mid-call returns whatever it had written, and neither should
 * take down the turn that asked. [NOT_RUN] is distinguishable from a command that exited
 * non-zero, which matters because the first means "try the next command" and the second means
 * "this phone does not have that one".
 */
fun shellOutcomeOf(encoded: String?): ShellOutcome {
  val text = encoded ?: return ShellOutcome(NOT_RUN, "")
  val newline = text.indexOf('\n')
  if (newline < 0) return ShellOutcome(text.trim().toIntOrNull() ?: NOT_RUN, "")
  val code = text.take(newline).trim().toIntOrNull() ?: return ShellOutcome(NOT_RUN, text)
  return ShellOutcome(code, text.substring(newline + 1))
}

/** The exit code for "this never ran at all", which no process produces. */
const val NOT_RUN = -1
