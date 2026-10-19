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

package com.lin.agent.model

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import android.util.Log
import com.lin.agent.core.tools.LaunchGuard
import com.lin.agent.core.web.urlEscape
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult

/**
 * Somebody's details out of the address book.
 *
 * Separate from calling them, which [PhoneTool] does. "What is Ben's number" and "what is
 * Sarah's email" are questions, and routing a question through a tool whose job is to dial
 * is how an assistant ends up ringing somebody you asked about.
 *
 * Read only, and only when asked. Nothing is cached and nothing is written down: the address
 * book is other people's details, and an assistant that quietly builds its own copy of it is
 * not a thing this app is going to do.
 */
class ContactTool(private val context: Context) : AgentTool {

  override val name = "contact"

  override val description =
    "Look somebody up in the address book and get their phone numbers and email addresses."

  override val parameters =
    listOf(
      ToolParameter(
        name = "name",
        type = ParamType.STRING,
        description = "Who to look up, as the user said it.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
      "Use contact to look somebody up rather than recalling a number, which you do not know."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["name"] as? String)?.trim().orEmpty()
    if (asked.isEmpty()) return ToolResult.Failed("Nobody was named.")
    if (
      context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return ToolResult.Failed(
        "Lin has not been given the address book, so it cannot look anybody up. Say so; the " +
          "user will be offered the permission."
      )
    }

    val numbers = lookUp(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      ContactsContract.CommonDataKinds.Phone.NUMBER, asked)
    val emails = lookUp(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
      ContactsContract.CommonDataKinds.Email.ADDRESS, asked)

    if (numbers.isEmpty() && emails.isEmpty()) {
      return ToolResult.Failed("There is nobody called \"$asked\" in the address book.")
    }

    return ToolResult.Ok(
      buildString {
        append("In the address book:")
        (numbers + emails)
          .groupBy({ it.first }, { it.second })
          .forEach { (who, details) ->
            append("\n- ").append(who).append(": ").append(details.distinct().joinToString(", "))
          }
      }
    )
  }

  /** Name and value pairs for everybody matching, loosely, what was asked. */
  private fun lookUp(uri: Uri, valueColumn: String, asked: String): List<Pair<String, String>> =
    runCatching {
        val found = mutableListOf<Pair<String, String>>()
        context.contentResolver
          .query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, valueColumn),
            // Loose, because the model is passing on a name somebody said out loud and "Ben"
            // should reach "Ben Elbit". The argument is bound rather than concatenated: a
            // name with an apostrophe in it is both common and, spliced into SQL, a crash.
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$asked%"),
            null,
          )
          ?.use { cursor ->
            while (cursor.moveToNext() && found.size < MOST) {
              val who = cursor.getString(0).orEmpty()
              val value = cursor.getString(1).orEmpty()
              if (who.isNotEmpty() && value.isNotEmpty()) found.add(who to value)
            }
          }
        found
      }
      .onFailure { Log.w(TAG, "Could not read contacts", it) }
      .getOrDefault(emptyList())

  private companion object {
    const val TAG = "LinContact"

    /** Enough for a household of people with the same surname; short of the whole book. */
    const val MOST = 20
  }
}

/**
 * Directions to somewhere, in whatever maps app the phone uses.
 *
 * Opening navigation rather than reading a route back: a route is a thing to follow on a
 * screen while driving, not a paragraph for a model to summarise, and every phone already
 * has an app that does it well.
 */
class NavigateTool(private val context: Context, private val guard: LaunchGuard) : AgentTool {

  override val name = "navigate"

  override val description =
    "Open directions to a place in the phone's maps app. Say that navigation is open, not " +
      "that you know the route."

  override val parameters =
    listOf(
      ToolParameter(
        name = "destination",
        type = ParamType.STRING,
        description = "Where to go: an address, a place name, or something like 'home'.",
      ),
      ToolParameter(
        name = "walking",
        type = ParamType.BOOLEAN,
        description = "True to walk rather than drive.",
        required = false,
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
      "navigate opens directions in the maps app; say navigation is open rather than " +
      "describing a route you have not seen."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val where = (arguments["destination"] as? String)?.trim().orEmpty()
    if (where.isEmpty()) return ToolResult.Failed("Nowhere was named.")
    val walking = arguments["walking"] as? Boolean ?: false
    if (!guard.allow("navigate", System.currentTimeMillis())) {
      return ToolResult.Ok("Done - directions to $where are already open.")
    }

    val escaped = urlEscape(where)
    // google.navigation starts turn-by-turn directly; geo: opens the place and is what a
    // phone without Google Maps answers. Tried in that order, because the first is what
    // somebody asking for directions while driving actually wants.
    val routes =
      listOf(
        "google.navigation:q=$escaped&mode=${if (walking) "w" else "d"}",
        "geo:0,0?q=$escaped",
      )
    val opened =
      routes.any { uri ->
        runCatching {
            context.startActivity(
              Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
          }
          .getOrDefault(false)
      }

    return if (opened) ToolResult.Ok("Done - directions to $where are open on screen.")
    else ToolResult.Failed("This phone has no maps app that would take directions to $where.")
  }
}

/**
 * The state of the phone itself: battery, storage, what it is connected to.
 *
 * Small, and asked more than it looks. "Have I got enough battery to leave now", "why is this
 * so slow", "am I on wifi" are all ordinary questions that Lin could not answer about the
 * device it was running on.
 */
class DeviceStatusTool(private val context: Context) : AgentTool {

  override val name = "device_status"

  override val description =
    "How the phone itself is doing: battery level, whether it is charging, free storage, and " +
      "what it is connected to."

  override val parameters = emptyList<ToolParameter>()

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val said = listOfNotNull(battery(), storage(), network())
    return if (said.isEmpty()) ToolResult.Failed("Lin could not read anything about the phone.")
    else ToolResult.Ok(said.joinToString("\n"))
  }

  private fun battery(): String? =
    runCatching {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return null
        val charging = manager.isCharging
        "Battery: $level%" + if (charging) ", charging." else "."
      }
      .getOrNull()

  private fun storage(): String? =
    runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val totalGb = stat.totalBytes / 1_000_000_000.0
        "Storage: %.1f GB free of %.1f GB.".format(freeGb, totalGb)
      }
      .getOrNull()

  private fun network(): String? =
    runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities =
          manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return "Network: nothing connected."
        val how =
          when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "a VPN"
            else -> "something"
          }
        // Connected and working are different questions, and the second is the one somebody
        // asking "is the internet down" means.
        val working = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        "Network: on $how" + if (working) ", reaching the internet." else ", but not reaching the internet."
      }
      .getOrNull()
}
