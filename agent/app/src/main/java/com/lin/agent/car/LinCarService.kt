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

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Lin, as an icon on the car's screen.
 *
 * The car cannot show the app. Android Auto draws its own screens from templates an app hands
 * it, so this is not Lin's interface shrunk - it is a different interface with two things on
 * it, which is all a screen read at 70mph should have: a button that opens the microphone, and
 * the few things worth knowing while driving.
 *
 * **The host is not trusted by default.** Anything on the phone could claim to be a car and
 * bind this service, and what it would get is a button that turns on the microphone. So the
 * release build accepts only the hosts the library ships signatures for, and a debug build
 * accepts anything - which is what makes the desktop head unit work while developing.
 *
 * One caveat worth writing down rather than discovering in a car park: Google's own testing
 * guide says the "unknown sources" developer setting **does not** apply to apps built with the
 * Car App Library. A side-loaded Lin may therefore not appear on a real head unit at all, even
 * with developer mode on, and the documented path for that is the desktop head unit or a Play
 * internal track. Nothing in this file can change that; it is a distribution rule.
 */
class LinCarService : CarAppService() {

  override fun createHostValidator(): HostValidator =
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
      // Debug builds only. This accepts any caller, which is the point: the desktop head unit
      // is not a signed Google host.
      HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
      HostValidator.Builder(applicationContext)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        .build()
    }

  override fun onCreateSession(): Session = LinCarSession()
}

/** One connection to a car. Lin has a single screen, so this has nothing else to do. */
class LinCarSession : Session() {
  override fun onCreateScreen(intent: Intent): Screen = LinCarScreen(carContext)
}
