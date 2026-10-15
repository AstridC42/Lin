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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.lin.agent.core.privileged.ShellCommand
import com.lin.agent.core.privileged.ShellOutcome
import com.lin.agent.core.privileged.ShizukuState
import com.lin.agent.core.privileged.shellOutcomeOf
import com.lin.agent.core.privileged.shellWorked
import com.lin.agent.core.privileged.shizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Lin's borrowed privileges, and everything that can go wrong getting them.
 *
 * Shizuku is how an app on an unrooted phone does what only the ADB shell may do. The user
 * installs it, pairs it once over Wireless debugging, and allows apps one at a time; a service
 * then runs under the `shell` user and lends that identity out over a Binder. Lin uses it for
 * exactly one thing: actually setting the switches Android took away from apps - Wi-Fi in
 * Android 10, Bluetooth in 13, NFC never having had an API at all - instead of opening the
 * panel and asking the person to tap.
 *
 * Every call into Shizuku here is wrapped, and not out of caution: the API throws
 * `IllegalStateException` from its *static* methods when the binder is not alive, which is the
 * normal state of a phone that has just restarted. An unwrapped `pingBinder` on the way to
 * drawing a settings screen would close the app.
 *
 * What is decided rather than done lives in `core`: [shizukuState] for which of the five
 * situations this is, [com.lin.agent.core.privileged.commandsFor] for what to run, and
 * [shellWorked] for whether it worked - because `svc` answers a subcommand it does not have by
 * printing help and exiting zero, and a tool that trusted the exit code would say "done" to a
 * help message.
 */
@Singleton
class Privileged
@Inject
constructor(@ApplicationContext private val context: Context) {

  @Volatile private var bound: IPrivileged? = null

  /** Which of the five situations the phone is in. Asked every time; none of it is stable. */
  fun state(): ShizukuState =
    shizukuState(
      installed = isInstalled(),
      running = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
      permitted = isPermitted(),
      refused = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false),
    )

  fun isReady() = state() == ShizukuState.READY

  /**
   * Asks Shizuku to allow Lin. Shizuku shows its own prompt; this only starts it.
   *
   * Returns whether the request got as far as being shown, which is not whether it was
   * allowed - the answer arrives later, in Shizuku's own dialog, and the caller has to say so
   * rather than report success.
   */
  fun request(): Boolean =
    runCatching {
        if (Shizuku.isPreV11()) return false
        Shizuku.requestPermission(REQUEST_CODE)
        true
      }
      .getOrDefault(false)

  /** Opens Shizuku, for the states that have to be fixed inside it. */
  fun openShizuku(): Boolean =
    PACKAGES.any { packageName ->
      runCatching {
          val intent =
            context.packageManager.getLaunchIntentForPackage(packageName)
              ?: return@runCatching false
          context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          true
        }
        .getOrDefault(false)
    }

  /** The page Shizuku is installed from, for the state where it is not installed. */
  fun openWhereToGetShizuku(): Boolean =
    runCatching {
        context.startActivity(
          Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
      }
      .getOrDefault(false)

  /**
   * Runs the first of [commands] that works, and says whether any did.
   *
   * In order and stopping at the first success, because the list is the same thing written the
   * way two different Android versions accept it - see
   * [com.lin.agent.core.privileged.commandsFor]. Running the rest after one has worked would
   * set the switch twice.
   */
  suspend fun runFirstThatWorks(commands: List<ShellCommand>): ShellOutcome? {
    if (commands.isEmpty()) return null
    val service = service() ?: return null
    var last: ShellOutcome? = null
    for (command in commands) {
      val outcome =
        withContext(Dispatchers.IO) {
          shellOutcomeOf(runCatching { service.run(command.argv.toTypedArray()) }.getOrNull())
        }
      last = outcome
      if (shellWorked(outcome.exitCode, outcome.output)) return outcome
      Log.i(TAG, "${command.argv.first()} did not work: ${outcome.exitCode} ${outcome.output.take(120)}")
    }
    return last
  }

  /**
   * The privileged service, binding it if it is not bound.
   *
   * Kept once bound, because starting a process under the shell user takes long enough to feel
   * - a second or so - and this is on the path of somebody saying "turn the wi-fi off".
   *
   * Not a daemon. A daemon service survives Lin being closed, which means a process with the
   * shell's privileges sitting there after the app that asked for it has gone; the cost of not
   * being one is that first command after a launch.
   */
  private suspend fun service(): IPrivileged? {
    bound?.let {
      // A binder whose process has died is still a non-null object, and calling it throws.
      if (runCatching { it.asBinder().isBinderAlive }.getOrDefault(false)) return it
      bound = null
    }
    if (!isReady()) return null

    val arrived = CompletableDeferred<IPrivileged?>()
    val connection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
          val service = binder?.takeIf { it.pingBinder() }?.let { IPrivileged.Stub.asInterface(it) }
          bound = service
          arrived.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          bound = null
          // Completed rather than left hanging: a service that disconnects before it ever
          // connected would otherwise hold the caller until the timeout.
          arrived.complete(null)
        }
      }

    val asked =
      runCatching {
          Shizuku.bindUserService(
            Shizuku.UserServiceArgs(ComponentName(context, PrivilegedService::class.java))
              .daemon(false)
              .processNameSuffix("privileged")
              .debuggable(false)
              .version(SERVICE_VERSION),
            connection,
          )
          true
        }
        .onFailure { Log.w(TAG, "Could not ask Shizuku for the service", it) }
        .getOrDefault(false)
    if (!asked) return null

    return withTimeoutOrNull(BIND_MS) { arrived.await() }
  }

  private fun isInstalled(): Boolean =
    PACKAGES.any { packageName ->
      runCatching {
          context.packageManager.getPackageInfo(packageName, 0)
          true
        }
        .getOrDefault(false)
    }

  private fun isPermitted(): Boolean =
    runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
      }
      .getOrDefault(false)

  private companion object {
    const val TAG = "LinPrivileged"
    const val REQUEST_CODE = 4219

    /**
     * Bumped when [PrivilegedService] changes.
     *
     * Shizuku keeps a running service across rebinds, so without this a new build would talk
     * to the old version of the class until the phone restarted - which during development
     * reads as the change not having worked.
     */
    const val SERVICE_VERSION = 1

    /** Long enough to start a process under another user, short enough not to look stuck. */
    const val BIND_MS = 8_000L

    /** Shizuku proper, and Sui, which is the rooted build of the same thing. */
    val PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")

    const val SHIZUKU_SITE = "https://shizuku.rikka.app/"
  }
}
