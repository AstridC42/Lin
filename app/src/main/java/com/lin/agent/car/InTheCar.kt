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

package com.lin.agent.car

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Whether the phone is in a car right now.
 *
 * Asked of the system's own car mode, which is what Android Auto turns on while it projects
 * and what a car dock sets. It is the only signal available without a permission: reading the
 * connected Bluetooth device's name needs BLUETOOTH_CONNECT, and asking for that to find out
 * whether somebody is driving is a poor trade.
 *
 * The limits are worth being plain about. This is true while Android Auto is running and false
 * in a car connected only by Bluetooth, which is the case Lin actually works best in - so
 * nothing here may *depend* on it being right. It decides whether to speak an answer and
 * whether a call goes straight out, both of which are safe to get wrong in the conservative
 * direction, and nothing else.
 */
fun inCar(context: Context): Boolean =
  runCatching {
      context.getSystemService(UiModeManager::class.java)?.currentModeType ==
        Configuration.UI_MODE_TYPE_CAR
    }
    .getOrDefault(false)
