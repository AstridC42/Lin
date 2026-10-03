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

import android.Manifest
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A quick settings tile that starts and stops listening for the wake phrase.
 *
 * Reaching into the app to toggle this is exactly the wrong shape for when it matters - getting
 * into a car, putting the phone in a mount. A tile is two swipes from anywhere, including the lock
 * screen, and it is also the fastest way to turn continuous recording *off* in a room where it
 * should not be running, which matters more.
 */
@AndroidEntryPoint
class WakeWordTileService : TileService() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onStartListening() {
    super.onStartListening()
    refresh()
  }

  override fun onClick() {
    super.onClick()
    val settings = dataStoreRepository.readAssistantSettings()
    val turningOn = !settings.wakeWordEnabled

    if (turningOn && !hasMicrophonePermission()) {
      // A tile cannot request a runtime permission. Sending the user into the app is the only way
      // to ask, and enabling the setting anyway would leave a switch that is on while nothing
      // listens.
      startActivityAndCollapse(WakeWordSettings.openAppPendingIntent(this))
      return
    }

    dataStoreRepository.saveAssistantSettings(
      settings.toBuilder().setWakeWordEnabled(turningOn).build()
    )
    if (turningOn) {
      WakeWordService.start(this, settings.wakeWordPhrase)
    } else {
      WakeWordService.stop(this)
    }
    refresh()
  }

  private fun refresh() {
    val enabled = dataStoreRepository.readAssistantSettings().wakeWordEnabled
    qsTile?.apply {
      state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
      updateTile()
    }
  }

  private fun hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
}
