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

package com.lin.agent.core.phone

/** Something on the phone that is either on or off. */
enum class Switch {
  FLASHLIGHT,
  WIFI,
  BLUETOOTH,
  NFC,
  DO_NOT_DISTURB,
  AIRPLANE_MODE,
}

/**
 * How far Lin can get with a switch, which is not the same question as whether it exists.
 *
 * Android spent several versions taking these away from apps, one at a time and for good
 * reasons: an app that can turn your Wi-Fi off is an app that can turn your Wi-Fi off. What is
 * left is uneven, and pretending otherwise is how an assistant comes to say "done" to something
 * that did not happen.
 */
enum class Reach {
  /** Lin does it, and it is done when Lin says so. */
  DIRECT,

  /** Lin can only open the place where it is done. The user taps; Lin must not claim it did. */
  OPENS_IT,

  /** Lin needs a permission it has not been given yet, and can only ask for it. */
  NEEDS_PERMISSION,
}

/**
 * What Lin can actually do about each switch, as of Android 16.
 *
 * The flashlight is the only one an app may simply set - the torch has never needed a
 * permission, because the worst it can do is waste battery in your pocket.
 *
 * Wi-Fi was taken from apps in Android 10, Bluetooth in Android 13, and NFC never had an API at
 * all; `WifiManager.setWifiEnabled` and `BluetoothAdapter.enable` are still in the SDK and still
 * compile, which is the trap - they return false rather than failing loudly, so code that calls
 * them looks like it works. For those, the honest best is the system's own panel, which is one
 * tap and shows the user exactly what is being changed.
 *
 * Aeroplane mode has no app-facing route of any kind; only the settings screen.
 *
 * Do Not Disturb can be set, but only once the user has granted notification policy access -
 * which is a different grant from notification access and has to be asked for separately.
 */
fun reachOf(switch: Switch, hasPolicyAccess: Boolean = false): Reach =
  when (switch) {
    Switch.FLASHLIGHT -> Reach.DIRECT
    Switch.DO_NOT_DISTURB -> if (hasPolicyAccess) Reach.DIRECT else Reach.NEEDS_PERMISSION
    Switch.WIFI, Switch.BLUETOOTH, Switch.NFC, Switch.AIRPLANE_MODE -> Reach.OPENS_IT
  }

/**
 * Which switch somebody meant.
 *
 * Written for what people say rather than for what the setting is called: "torch" and "light"
 * are the flashlight, "internet" is Wi-Fi, "silent" and "quiet" are Do Not Disturb. A model
 * passing the word through from the user is the normal case, so this has to take the user's
 * word rather than an enum name.
 *
 * Null rather than a guess. Turning the wrong thing off is worse than saying the word was not
 * understood, and "mobile data" - which Lin cannot touch at all - must not quietly become Wi-Fi.
 */
fun switchNamed(text: String): Switch? {
  val said = text.trim().lowercase().replace("-", " ").replace("_", " ")
  return when {
    said.isEmpty() -> null
    said.containsAny("flashlight", "flash light", "torch", "flash") -> Switch.FLASHLIGHT
    // Exactly "light", not any phrase containing it: "night light" and "light mode" are
    // different settings, and turning the torch on for either would be a surprise.
    said == "light" || said == "the light" -> Switch.FLASHLIGHT
    said.containsAny("wifi", "wi fi", "wireless", "internet") -> Switch.WIFI
    said.containsAny("bluetooth", "blue tooth") || said == "bt" -> Switch.BLUETOOTH
    said.contains("nfc") || said.contains("contactless") -> Switch.NFC
    said.containsAny("do not disturb", "dnd", "silent", "quiet mode", "focus") ->
      Switch.DO_NOT_DISTURB
    said.containsAny("airplane", "aeroplane", "flight mode", "plane mode") -> Switch.AIRPLANE_MODE
    else -> null
  }
}

/**
 * Whether they asked for it on or off.
 *
 * Null when the words do not say, which a caller has to handle rather than default: defaulting
 * to "on" turns the torch on in somebody's pocket when they said "flashlight?" to ask whether
 * it was.
 */
fun wantedOn(text: String): Boolean? {
  // Whole words, not substrings. "on" is inside "phone", "front" and "contactless", and a
  // substring match on it reads "the flashlight on my phone" as an instruction.
  val words = text.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }
  return when {
    words.isEmpty() -> null
    // A question is not an instruction, and the difference is not in the words: "is the
    // flashlight on" and "turn the flashlight on" both end in "on". A small model will hand
    // the whole sentence through as the state it was asked for, so the sentence has to be
    // read for what it is - and null here means the caller says what the switch is doing
    // rather than changing it.
    asksRatherThanTells(text, words.first()) -> null
    words.any { it in OFF } -> false
    words.any { it in ON } -> true
    else -> null
  }
}

private fun asksRatherThanTells(text: String, firstWord: String) =
  text.trimEnd().endsWith("?") || firstWord in ASKING

private val ASKING =
  setOf("is", "are", "was", "were", "do", "does", "did", "what", "whats", "which", "whether",
    "check", "tell", "status", "how", "can", "could", "has", "have", "am")

private val OFF = setOf("off", "disable", "disabled", "stop", "kill", "false", "0", "no")
private val ON = setOf("on", "enable", "enabled", "start", "true", "1", "yes")
private val NOT_A_WORD = Regex("[^a-z0-9]+")

private fun String.containsAny(vararg words: String) = words.any { contains(it) }
