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

package com.google.ai.edge.gallery.voice

import android.service.voice.VoiceInteractionService
import android.util.Log

private const val TAG = "AGVoiceInteraction"

/**
 * Registers Lin as a digital assistant the system can hand control to.
 *
 * An `ACTION_ASSIST` intent filter alone, which is all the app had, is enough to appear in some
 * "assist app" lists but not to *be* the assistant. The assist gesture, holding the power button,
 * the Bluetooth headset button and the car's voice control all go to whichever app implements this
 * service; without it those routes have nowhere to land, which is the difference between an app
 * that can be opened and an assistant that can be summoned while driving.
 *
 * The work happens in [LinVoiceInteractionSession]. This class exists to be pointed at.
 */
class LinVoiceInteractionService : VoiceInteractionService() {

  override fun onReady() {
    super.onReady()
    Log.d(TAG, "Lin is ready as the device's voice interaction service")
  }

  /**
   * Called when the user picks this app as their digital assistant, and on boot thereafter.
   *
   * Nothing to prepare: sessions are created on demand. Deliberately not starting any always-on
   * audio here - see [LinVoiceInteractionSession] for why a custom wake word is not something
   * this service can offer.
   */
  override fun onShutdown() {
    Log.d(TAG, "No longer the device's voice interaction service")
    super.onShutdown()
  }
}
