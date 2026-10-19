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

/**
 * The runtime permission a tool cannot work without, by tool name.
 *
 * This exists because of a gap with no owner. A runtime permission can only be asked for by
 * something with a window on screen; a tool has no window, so when the weather tool found no
 * location grant all it could do was fail with a sentence - and the sentence went to a 2B
 * model, which turned "tell the user it is in settings, under Weather" into "I cannot tell you
 * the weather because I do not know your location. You can check the weather in your settings."
 * The person is then two guesses away from a switch that is one tap away.
 *
 * So the screen asks instead. The tool fails as before; the conversation notices *which* tool
 * failed, looks the permission up here, and - only if it is genuinely not granted - offers the
 * one button that fixes it. The model is not involved and cannot get it wrong.
 *
 * Only runtime permissions belong here. Notification access and screen access are granted on
 * a settings screen rather than in a dialog, and a button labelled "Allow" that opens a list
 * somewhere else is a worse answer than the sentence those tools already give.
 */
fun permissionNeededBy(toolName: String): String? =
  when (toolName) {
    "weather" -> COARSE_LOCATION
    "contact" -> READ_CONTACTS
    else -> null
  }

/**
 * Named here rather than taken from `android.Manifest`, because this module does not depend on
 * the framework and the name is a stable string either way.
 */
const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

const val READ_CONTACTS = "android.permission.READ_CONTACTS"
