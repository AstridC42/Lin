/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGDeviceActionsTool"

/** A phone number found in the device address book. */
data class ContactMatch(val name: String, val number: String, val label: String)

/**
 * Device control actions that a general purpose assistant is expected to handle: placing calls,
 * texting, alarms and timers, opening apps, maps and settings.
 *
 * Anything that reaches another person — a call or a text — is staged rather than sent. The dialer
 * or the SMS composer opens pre-filled and the user takes the final action, which keeps a
 * misheard name from calling the wrong contact. Direct dialing is only used when the user has
 * explicitly opted in via [allowDirectCalls] *and* granted `CALL_PHONE`.
 */
class DeviceActionsTool(
  private val context: Context,
  private val allowDirectCalls: () -> Boolean = { false },
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Look up phone numbers in the user's contacts. Call this before placing a call or sending " +
        "a text when the user referred to a person by name rather than by number."
  )
  fun findContact(
    @ToolParam(description = "All or part of the contact's name, for example 'Sam' or 'mum'.")
    name: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (name.isBlank()) {
        return@runBlocking failure("No name was given to look up.")
      }
      if (!ensurePermission(Manifest.permission.READ_CONTACTS)) {
        return@runBlocking failure(
          "The user did not grant access to contacts. Ask them for the phone number instead."
        )
      }
      sendProgress("Looking up \"$name\" in contacts", inProgress = true)
      val matches = queryContacts(name)
      if (matches.isEmpty()) {
        sendProgress("No contact matched \"$name\"", inProgress = false)
        return@runBlocking failure("No contact matches \"$name\".")
      }
      sendProgress(
        "Found ${matches.size} contact(s) for \"$name\"",
        inProgress = true,
        itemTitle = "Contact lookup",
        itemDescription = matches.joinToString("\n") { "- ${it.name}: ${it.number}" },
      )
      mapOf(
        "status" to "succeeded",
        "matches" to
          matches.map {
            mapOf("name" to it.name, "phone_number" to it.number, "label" to it.label)
          },
        "hint" to
          if (matches.size > 1) {
            "Several contacts matched. Ask the user which one before calling or texting."
          } else {
            "Use this phone number for the call or text."
          },
      )
    }
  }

  @Tool(
    description =
      "Call someone. Accepts either a contact name or a phone number. By default this opens the " +
        "dialer with the number filled in so the user places the call with one tap."
  )
  fun placePhoneCall(
    @ToolParam(
      description = "The contact name to call, or a phone number in national or E.164 format."
    )
    nameOrNumber: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (nameOrNumber.isBlank()) {
        return@runBlocking failure("No contact name or phone number was given.")
      }
      val resolved =
        resolveNumber(nameOrNumber) ?: return@runBlocking failure(
          "Could not find a phone number for \"$nameOrNumber\". Use findContact first, or ask " +
            "the user for the number."
        )
      if (resolved.ambiguousMatches.size > 1) {
        return@runBlocking mapOf(
          "status" to "needs_clarification",
          "matches" to
            resolved.ambiguousMatches.map {
              mapOf("name" to it.name, "phone_number" to it.number, "label" to it.label)
            },
          "hint" to "Ask the user which of these contacts to call, then call this tool again.",
        )
      }

      val number = resolved.number
      val direct =
        allowDirectCalls() &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
      val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
      val started = startActivity(Intent(action, "tel:${number.telUriEncode()}".toUri()))
      if (!started) {
        return@runBlocking failure("This device has no app that can place calls.")
      }
      sendProgress(
        if (direct) "Calling ${resolved.displayName}" else "Opening the dialer for ${resolved.displayName}",
        inProgress = false,
        itemTitle = "Phone call",
        itemDescription = "- ${resolved.displayName}\n- $number",
      )
      mapOf(
        "status" to "succeeded",
        "contact" to resolved.displayName,
        "phone_number" to number,
        "placed" to direct,
        "result" to
          if (direct) "The call is being placed."
          else "The dialer is open with the number filled in; the user taps to call.",
      )
    }
  }

  @Tool(
    description =
      "Open the SMS composer with a message ready to send. Accepts a contact name or a phone " +
        "number. The user presses send."
  )
  fun sendTextMessage(
    @ToolParam(description = "The contact name or phone number to text.") nameOrNumber: String,
    @ToolParam(description = "The message body.") message: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val resolved =
        resolveNumber(nameOrNumber) ?: return@runBlocking failure(
          "Could not find a phone number for \"$nameOrNumber\"."
        )
      if (resolved.ambiguousMatches.size > 1) {
        return@runBlocking mapOf(
          "status" to "needs_clarification",
          "matches" to
            resolved.ambiguousMatches.map {
              mapOf("name" to it.name, "phone_number" to it.number)
            },
          "hint" to "Ask the user which contact to text, then call this tool again.",
        )
      }
      val intent =
        Intent(Intent.ACTION_SENDTO, "smsto:${resolved.number.telUriEncode()}".toUri()).apply {
          putExtra("sms_body", message)
        }
      if (!startActivity(intent)) {
        return@runBlocking failure("This device has no messaging app.")
      }
      sendProgress(
        "Drafting a text to ${resolved.displayName}",
        inProgress = false,
        itemTitle = "Text message",
        itemDescription = "- To: ${resolved.displayName}\n- Body: $message",
      )
      mapOf(
        "status" to "succeeded",
        "contact" to resolved.displayName,
        "result" to "The messaging app is open with the draft; the user presses send.",
      )
    }
  }

  @Tool(description = "Set an alarm on the device's clock app.")
  fun setAlarm(
    @ToolParam(description = "Hour in 24-hour format, 0-23.") hour: Int,
    @ToolParam(description = "Minute, 0-59.") minute: Int,
    @ToolParam(description = "Optional label for the alarm.") label: String = "",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (hour !in 0..23 || minute !in 0..59) {
        return@runBlocking failure("$hour:$minute is not a valid time of day.")
      }
      val intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
          putExtra(AlarmClock.EXTRA_HOUR, hour)
          putExtra(AlarmClock.EXTRA_MINUTES, minute)
          if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
          putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
      if (!startActivity(intent)) {
        return@runBlocking failure("This device has no clock app that can set alarms.")
      }
      val time = "%02d:%02d".format(hour, minute)
      sendProgress("Alarm set for $time", inProgress = false)
      mapOf("status" to "succeeded", "time" to time, "label" to label)
    }
  }

  @Tool(description = "Start a countdown timer on the device's clock app.")
  fun setTimer(
    @ToolParam(description = "Duration of the timer in seconds.") seconds: Int,
    @ToolParam(description = "Optional label for the timer.") label: String = "",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (seconds <= 0) {
        return@runBlocking failure("A timer needs a positive duration.")
      }
      val intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
          putExtra(AlarmClock.EXTRA_LENGTH, seconds)
          if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
          putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
      if (!startActivity(intent)) {
        return@runBlocking failure("This device has no clock app that can set timers.")
      }
      sendProgress("Timer started for $seconds seconds", inProgress = false)
      mapOf("status" to "succeeded", "seconds" to seconds, "label" to label)
    }
  }

  @Tool(description = "Open an installed app by its display name, for example 'Spotify'.")
  fun openApp(
    @ToolParam(description = "The visible name of the app to open.") appName: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (appName.isBlank()) {
        return@runBlocking failure("No app name was given.")
      }
      val packageManager = context.packageManager
      val launcherIntent =
        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
      val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
      val needle = appName.trim().lowercase()
      val match =
        candidates.firstOrNull {
          it.loadLabel(packageManager).toString().equals(appName.trim(), ignoreCase = true)
        }
          ?: candidates.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase().contains(needle)
          }
          ?: return@runBlocking failure(
            "No installed app is called \"$appName\". Ask the user for the exact name."
          )

      val packageName = match.activityInfo.packageName
      val intent =
        packageManager.getLaunchIntentForPackage(packageName)
          ?: return@runBlocking failure("\"$appName\" cannot be launched from here.")
      if (!startActivity(intent)) {
        return@runBlocking failure("Could not open \"$appName\".")
      }
      val label = match.loadLabel(packageManager).toString()
      sendProgress("Opened $label", inProgress = false)
      mapOf("status" to "succeeded", "app" to label, "package" to packageName)
    }
  }

  @Tool(description = "Show a place, address or business on the map.")
  fun showOnMap(
    @ToolParam(description = "A place name, business or address to show.") place: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (place.isBlank()) return@runBlocking failure("No place was given.")
      val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=${place.uriEncode()}".toUri())
      if (!startActivity(intent)) {
        return@runBlocking failure("This device has no maps app.")
      }
      sendProgress("Showing $place on the map", inProgress = false)
      mapOf("status" to "succeeded", "place" to place)
    }
  }

  @Tool(description = "Start turn-by-turn navigation to a destination.")
  fun getDirections(
    @ToolParam(description = "Where the user wants to go.") destination: String,
    @ToolParam(description = "Travel mode: driving, walking, bicycling or transit.")
    mode: String = "driving",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (destination.isBlank()) return@runBlocking failure("No destination was given.")
      val modeCode =
        when (mode.trim().lowercase()) {
          "walk",
          "walking" -> "w"
          "bike",
          "bicycle",
          "bicycling" -> "b"
          "transit",
          "public transport" -> "r"
          else -> "d"
        }
      val uri =
        "google.navigation:q=${destination.uriEncode()}&mode=$modeCode".toUri()
      if (!startActivity(Intent(Intent.ACTION_VIEW, uri))) {
        // Fall back to a plain map lookup on devices without Google Maps.
        if (!startActivity(
          Intent(Intent.ACTION_VIEW, "geo:0,0?q=${destination.uriEncode()}".toUri())
        )) {
          return@runBlocking failure("This device has no navigation app.")
        }
      }
      sendProgress("Navigating to $destination", inProgress = false)
      mapOf("status" to "succeeded", "destination" to destination, "mode" to mode)
    }
  }

  @Tool(description = "Turn the camera flashlight (torch) on or off.")
  fun toggleFlashlight(
    @ToolParam(description = "True to switch the torch on, false to switch it off.") on: Boolean
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId =
          cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(
              android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
            ) == true
          } ?: return@runBlocking failure("This device has no flashlight.")
        cameraManager.setTorchMode(cameraId, on)
        sendProgress(if (on) "Flashlight on" else "Flashlight off", inProgress = false)
        mapOf("status" to "succeeded", "flashlight_on" to on)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to toggle flashlight", e)
        failure("Could not change the flashlight (${e.message ?: "unknown error"}).")
      }
    }
  }

  @Tool(description = "Open a page of the Android system settings.")
  fun openSystemSettings(
    @ToolParam(
      description =
        "Which settings page to open: wifi, bluetooth, sound, display, battery, data, location, " +
          "apps, storage, accessibility, date, or main for the top level."
    )
    section: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val action =
        when (section.trim().lowercase()) {
          "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
          "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
          "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
          "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
          "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
          "data", "mobile data", "cellular" -> Settings.ACTION_DATA_ROAMING_SETTINGS
          "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
          "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
          "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
          "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
          "date", "time" -> Settings.ACTION_DATE_SETTINGS
          "main", "settings" -> Settings.ACTION_SETTINGS
          else -> return@runBlocking failure("\"$section\" is not a settings page I can open.")
        }
      if (!startActivity(Intent(action))) {
        return@runBlocking failure("Could not open the $section settings.")
      }
      sendProgress("Opened $section settings", inProgress = false)
      mapOf("status" to "succeeded", "section" to section)
    }
  }

  @Tool(description = "Open a URL in the user's web browser.")
  fun openUrlInBrowser(
    @ToolParam(description = "The http or https URL to open.") url: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return@runBlocking failure("Only http and https URLs can be opened.")
      }
      if (!startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))) {
        return@runBlocking failure("This device has no browser.")
      }
      sendProgress("Opened $url", inProgress = false)
      mapOf("status" to "succeeded", "url" to url)
    }
  }

  /** A phone number resolved from either a raw number or a contact name. */
  private data class ResolvedNumber(
    val number: String,
    val displayName: String,
    val ambiguousMatches: List<ContactMatch> = emptyList(),
  )

  private suspend fun resolveNumber(nameOrNumber: String): ResolvedNumber? {
    val trimmed = nameOrNumber.trim()
    if (looksLikePhoneNumber(trimmed)) {
      return ResolvedNumber(number = trimmed, displayName = trimmed)
    }
    if (!ensurePermission(Manifest.permission.READ_CONTACTS)) {
      return null
    }
    val matches = queryContacts(trimmed)
    // Distinct people, not distinct numbers: one contact with a mobile and a work number is not
    // ambiguous enough to bother the user about.
    val distinctPeople = matches.distinctBy { it.name.lowercase() }
    return when {
      matches.isEmpty() -> null
      distinctPeople.size > 1 -> ResolvedNumber("", trimmed, ambiguousMatches = distinctPeople)
      else -> ResolvedNumber(number = matches.first().number, displayName = matches.first().name)
    }
  }

  private fun queryContacts(name: String): List<ContactMatch> {
    val matches = mutableListOf<ContactMatch>()
    try {
      val projection =
        arrayOf(
          ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
          ContactsContract.CommonDataKinds.Phone.NUMBER,
          ContactsContract.CommonDataKinds.Phone.TYPE,
          ContactsContract.CommonDataKinds.Phone.LABEL,
        )
      context.contentResolver
        .query(
          ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
          projection,
          "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
          arrayOf("%$name%"),
          "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
        )
        ?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(projection[0])
          val numberIndex = cursor.getColumnIndex(projection[1])
          val typeIndex = cursor.getColumnIndex(projection[2])
          val labelIndex = cursor.getColumnIndex(projection[3])
          while (cursor.moveToNext() && matches.size < MAX_CONTACT_MATCHES) {
            val number = cursor.getString(numberIndex)?.trim().orEmpty()
            if (number.isEmpty()) continue
            val displayName = cursor.getString(nameIndex)?.trim().orEmpty().ifEmpty { number }
            val label =
              ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                  context.resources,
                  if (typeIndex >= 0) cursor.getInt(typeIndex) else 0,
                  if (labelIndex >= 0) cursor.getString(labelIndex) else null,
                )
                .toString()
            matches.add(ContactMatch(name = displayName, number = number, label = label))
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query contacts", e)
    }
    return matches
  }

  /** Requests [permission] through the chat UI when it has not been granted yet. */
  private suspend fun ensurePermission(permission: String): Boolean {
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
      return true
    }
    val channel = executionContext?.actionChannel ?: return false
    val action = RequestPermissionToolAction(permission = permission)
    channel.send(action)
    return action.result.await()
  }

  private fun startActivity(intent: Intent): Boolean {
    return try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No activity for intent ${intent.action}", e)
      false
    } catch (e: SecurityException) {
      Log.w(TAG, "Not allowed to start intent ${intent.action}", e)
      false
    }
  }

  private suspend fun sendProgress(
    label: String,
    inProgress: Boolean,
    itemTitle: String = "",
    itemDescription: String = "",
  ) {
    executionContext
      ?.actionChannel
      ?.send(
        SkillProgressToolAction(
          label = label,
          inProgress = inProgress,
          addItemTitle = itemTitle,
          addItemDescription = itemDescription,
        )
      )
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  companion object {
    private const val MAX_CONTACT_MATCHES = 8
    private val PHONE_NUMBER_REGEX = Regex("^[+(]?[0-9][0-9()\\-.\\s]{4,}$")

    fun looksLikePhoneNumber(value: String): Boolean = PHONE_NUMBER_REGEX.matches(value.trim())

    private fun String.uriEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    /** `tel:` accepts digits and a few separators; everything else is dropped. */
    private fun String.telUriEncode(): String = filter { it.isDigit() || it in "+*#;," }
  }
}
