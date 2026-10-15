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

package com.lin.agent.privileged

import com.lin.agent.core.privileged.encodeShellOutcome
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The half of Lin that runs as the shell user.
 *
 * Shizuku starts this class in its own process, under the same identity `adb shell` has, and
 * hands the app a Binder to it. That is the whole mechanism: there is no root, no system
 * signature and no hidden API - this is Lin's own code with somebody else's privileges, and
 * the user granted them one app at a time in Shizuku.
 *
 * It runs commands and says what happened. It does not decide which commands: that is
 * [com.lin.agent.core.privileged.commandsFor], which is in `core` with tests, because a table
 * of shell commands running as the shell user is the last thing that should be written
 * untested. Nothing here reads a setting, talks to the model or touches the conversation.
 *
 * No Hilt. A Hilt-injected class in a process Shizuku starts would need the whole application
 * component built in that process, for a service whose only dependency is ProcessBuilder.
 */
class PrivilegedService : IPrivileged.Stub() {

  /**
   * Runs [argv] and returns its exit code and output together.
   *
   * Output merged rather than kept apart, because every caller of this wants to know whether
   * the command complained and not which stream it complained on - `svc` prints its usage to
   * stdout and the shell prints "not found" to stderr, and both mean the same thing here.
   *
   * Timed out, because this is a Binder call from the UI's own coroutine and a command that
   * never returns would otherwise hold it for ever. Nothing on the list this is called with
   * takes longer than a moment; anything that does has gone wrong.
   */
  override fun run(argv: Array<String>?): String {
    val command = argv?.filter { it.isNotBlank() }.orEmpty()
    if (command.isEmpty()) return encodeShellOutcome(NOTHING_TO_RUN, "no command was given")

    return try {
      val process =
        ProcessBuilder(command)
          .redirectErrorStream(true)
          // Started from a known directory. A privileged process inheriting whatever working
          // directory it happened to be given is one relative path away from a surprise.
          .directory(File("/"))
          .start()

      // Read before waiting, not after. A process whose output fills the pipe buffer blocks
      // writing to it, for ever, while this waits for it to exit - which is a deadlock that
      // only shows up on the one command that happens to be chatty.
      val said = process.inputStream.bufferedReader().use { it.readText() }
      val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        return encodeShellOutcome(TIMED_OUT, "the command did not finish: $said")
      }
      encodeShellOutcome(process.exitValue(), said)
    } catch (failure: Throwable) {
      // Reported rather than thrown. A Throwable crossing a Binder arrives at the caller as a
      // RuntimeException with no detail, in a process that is about to be killed - and the
      // caller has a list of other commands to try, which it cannot do if this takes it down.
      encodeShellOutcome(THREW, failure.toString())
    }
  }

  override fun destroy() {
    // Shizuku calls this when it is taking the service away. There is nothing held open here -
    // every process is waited for and closed inside run - so there is nothing to release, and
    // exiting is what stops the process rather than leaving it parked under the shell user.
    System.exit(0)
  }

  private companion object {
    const val TIMEOUT_SECONDS = 10L
    const val NOTHING_TO_RUN = -2
    const val TIMED_OUT = -3
    const val THREW = -4
  }
}
