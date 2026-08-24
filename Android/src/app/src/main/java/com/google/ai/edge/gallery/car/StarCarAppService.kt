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

package com.google.ai.edge.gallery.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Makes Star Storyteller appear on the car's screen under Android Auto.
 *
 * ### What a car app is allowed to be
 *
 * Android Auto does not let an app draw its own interface. Everything on the car screen is built
 * from a fixed set of templates the host renders, with hard caps on how many rows and how much
 * text may appear, and how often a screen may change while the car is moving. A scrolling
 * conversation is not one of the available shapes and would not be permitted if it were: reading
 * a model's paragraph at speed is exactly what the driver distraction rules exist to prevent.
 *
 * So this is not the chat screen in the car. It is a way to reach the assistant without touching
 * the phone: a short list of things worth doing while driving, and replies spoken rather than
 * displayed.
 *
 * ### Getting it to appear
 *
 * A sideloaded app is not visible in Android Auto until the phone's Android Auto settings have
 * developer mode turned on and "Unknown sources" enabled. That is a property of Android Auto for
 * apps outside the Play Store, not something the app can arrange for itself.
 */
class StarCarAppService : CarAppService() {

  /**
   * Only the real Android Auto and Automotive hosts may connect.
   *
   * `hosts_allowlist_sample` is named misleadingly: despite "sample" it is the Car App Library's
   * list of Google-signed host certificates, and it is what the documentation tells apps to pass
   * here. `ALLOW_ALL_HOSTS_VALIDATOR` is the one meant only for local development - it would let
   * any app on the phone drive this service - so it is not used, even though it is simpler.
   */
  override fun createHostValidator(): HostValidator =
    HostValidator.Builder(applicationContext)
      .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
      .build()

  override fun onCreateSession(sessionInfo: SessionInfo): Session = StarCarSession()

  override fun onCreateSession(): Session = StarCarSession()
}

/** One connection to the car. */
class StarCarSession : Session() {
  override fun onCreateScreen(intent: Intent) = StarCarScreen(carContext)
}
