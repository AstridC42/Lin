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

import com.lin.agent.core.phone.Reach
import com.lin.agent.core.phone.Switch
import com.lin.agent.core.phone.reachOf
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PrivilegeTest {

  @Test
  fun `everything in place is ready`() {
    assertEquals(
      ShizukuState.READY,
      shizukuState(installed = true, running = true, permitted = true),
    )
    assertTrue(ShizukuState.READY.isReady())
  }

  @Test
  fun `not installed comes before everything else`() {
    // The order is the content of this function. A phone that had Shizuku and does not any
    // more still has the permission grant recorded, so permitted can be true with nothing to
    // be permitted by - and reporting READY there means Lin says "done" to a command that
    // never ran.
    assertEquals(
      ShizukuState.NOT_INSTALLED,
      shizukuState(installed = false, running = true, permitted = true, refused = true),
    )
  }

  @Test
  fun `installed but stopped is its own answer`() {
    // The common case, and the reason it must not read as NOT_PERMITTED: Shizuku has to be
    // started again after every restart of the phone. Sending somebody to the permission list
    // for that is sending them to the wrong screen.
    assertEquals(
      ShizukuState.NOT_RUNNING,
      shizukuState(installed = true, running = false, permitted = true),
    )
    assertEquals(
      ShizukuState.NOT_RUNNING,
      shizukuState(installed = true, running = false, permitted = false, refused = true),
    )
  }

  @Test
  fun `running and unasked is worth asking about`() {
    assertEquals(
      ShizukuState.NOT_PERMITTED,
      shizukuState(installed = true, running = true, permitted = false),
    )
  }

  @Test
  fun `told never to ask again is not worth asking about`() {
    assertEquals(
      ShizukuState.REFUSED,
      shizukuState(installed = true, running = true, permitted = false, refused = true),
    )
  }

  @Test
  fun `a granted permission wins over a stale do-not-ask flag`() {
    // Shizuku's "should show rationale" flag survives the user going back and allowing Lin
    // from inside Shizuku itself. Reading refused first would report REFUSED on a phone where
    // the privilege is working.
    assertEquals(
      ShizukuState.READY,
      shizukuState(installed = true, running = true, permitted = true, refused = true),
    )
  }

  @Test
  fun `every state but ready says what to do about it`() {
    for (state in ShizukuState.entries) {
      val advice = whatToDoAbout(state)
      if (state == ShizukuState.READY) {
        assertEquals("", advice)
      } else {
        assertTrue("$state has nothing to tell the user", advice.isNotBlank())
        // The one thing it must not be is a sentence about an API. Every one of these is
        // fixable by the person holding the phone, so each has to name the app they fix it in.
        assertTrue("$state does not name the app: $advice", advice.contains("Shizuku"))
      }
    }
  }

  @Test
  fun `the four taken-away switches become direct with the shell behind them`() {
    for (switch in listOf(Switch.WIFI, Switch.BLUETOOTH, Switch.NFC, Switch.AIRPLANE_MODE)) {
      assertEquals("$switch without privilege", Reach.OPENS_IT, reachOf(switch))
      assertEquals("$switch with privilege", Reach.DIRECT, reachOf(switch, privileged = true))
    }
  }

  @Test
  fun `privilege does not change the two that were never taken away`() {
    assertEquals(Reach.DIRECT, reachOf(Switch.FLASHLIGHT, privileged = true))
    // Do Not Disturb has a real API behind a grant the user gives once, and borrowing the
    // shell to avoid asking for it would be going round the person rather than asking them.
    assertEquals(
      Reach.NEEDS_PERMISSION,
      reachOf(Switch.DO_NOT_DISTURB, hasPolicyAccess = false, privileged = true),
    )
    assertEquals(
      Reach.DIRECT,
      reachOf(Switch.DO_NOT_DISTURB, hasPolicyAccess = true, privileged = true),
    )
  }

  @Test
  fun `each taken-away switch has a command and the two others have none`() {
    for (switch in listOf(Switch.WIFI, Switch.BLUETOOTH, Switch.NFC, Switch.AIRPLANE_MODE)) {
      assertTrue("$switch on", commandsFor(switch, on = true).isNotEmpty())
      assertTrue("$switch off", commandsFor(switch, on = false).isNotEmpty())
    }
    assertEquals(emptyList<ShellCommand>(), commandsFor(Switch.FLASHLIGHT, on = true))
    assertEquals(emptyList<ShellCommand>(), commandsFor(Switch.DO_NOT_DISTURB, on = true))
  }

  @Test
  fun `on and off differ for every switch that has commands`() {
    // A table that returns the same command either way turns "turn wi-fi off" into "turn
    // wi-fi on" and still reports success, which is the worst shape this bug could take.
    for (switch in Switch.entries) {
      val on = commandsFor(switch, on = true)
      if (on.isEmpty()) continue
      assertFalse("$switch does the same thing both ways", on == commandsFor(switch, on = false))
    }
  }

  @Test
  fun `no command is empty or has an empty word in it`() {
    for (switch in Switch.entries) {
      for (both in listOf(true, false)) {
        for (command in commandsFor(switch, both)) {
          assertTrue("$switch $both has an empty command", command.argv.isNotEmpty())
          assertTrue("$switch $both: ${command.argv}", command.argv.all { it.isNotBlank() })
        }
      }
    }
  }

  @Test
  fun `the airplane mode fallback sets the value and announces it together`() {
    val fallback = commandsFor(Switch.AIRPLANE_MODE, on = true).last().argv.last()
    // Both halves in one shell, because a settings row changed with nothing listening is a
    // phone that displays aeroplane mode on with the radios still up.
    assertTrue(fallback, fallback.contains("airplane_mode_on 1"))
    assertTrue(fallback, fallback.contains("am broadcast"))
    assertTrue(fallback, fallback.contains("state true"))

    val off = commandsFor(Switch.AIRPLANE_MODE, on = false).last().argv.last()
    assertTrue(off, off.contains("airplane_mode_on 0"))
    assertTrue(off, off.contains("state false"))
  }

  @Test
  fun `a clean run counts as done`() {
    assertTrue(shellWorked(0, ""))
    assertTrue(shellWorked(0, "\n"))
  }

  @Test
  fun `a non-zero exit is not done`() {
    assertFalse(shellWorked(1, ""))
    assertFalse(shellWorked(255, "fine"))
  }

  @Test
  fun `svc printing its usage is not done even though it exits zero`() {
    // The reason this function exists at all. `svc` answers a subcommand it does not have by
    // printing help and exiting zero, so a phone whose svc has no nfc would otherwise have
    // Lin report success at having printed a help message.
    val help =
      """
      Available commands:
      help             Show information about the subcommands
      power            Control the power manager
      data             Control mobile data connectivity
      wifi             Control the Wi-Fi manager
      """
        .trimIndent()
    assertFalse(shellWorked(0, help))
    assertFalse(shellWorked(0, "Usage: cmd wifi set-wifi-enabled [enabled|disabled]"))
  }

  @Test
  fun `a refusal is not done`() {
    assertFalse(shellWorked(0, "java.lang.SecurityException: Permission Denial"))
    assertFalse(shellWorked(0, "Can't find service: wifi"))
    assertFalse(shellWorked(0, "sh: svc: inaccessible or not found"))
  }

  @Test
  fun `the complaints are matched whatever the case`() {
    assertFalse(shellWorked(0, "UNKNOWN COMMAND"))
    assertFalse(shellWorked(0, "Error: something"))
  }
}
