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

import com.lin.agent.core.tools.QuickCommand
import com.lin.agent.core.tools.quickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundTest {

  private fun mode(m: String) = QuickCommand("sound_mode", mapOf("mode" to m))

  private fun volume(level: String, stream: String? = null) =
    QuickCommand("volume", if (stream == null) mapOf("level" to level) else mapOf("level" to level, "stream" to stream))

  @Test
  fun ringerModesAreInstant() {
    assertEquals(mode("silent"), quickCommand("silent mode"))
    assertEquals(mode("silent"), quickCommand("Put the phone in silent mode"))
    assertEquals(mode("vibrate"), quickCommand("vibrate"))
    assertEquals(mode("vibrate"), quickCommand("put my phone on vibrate"))
    assertEquals(mode("vibrate"), quickCommand("vibration mode"))
    assertEquals(mode("sound"), quickCommand("sound mode"))
    assertEquals(mode("sound"), quickCommand("turn off silent mode"))
    assertEquals(mode("sound"), quickCommand("turn off vibrate"))
    assertEquals(mode("sound"), quickCommand("normal mode"))
  }

  @Test
  fun volumeIsInstant() {
    assertEquals(volume("50%"), quickCommand("volume 50"))
    assertEquals(volume("30%"), quickCommand("set the volume to 30%"))
    assertEquals(volume("30%", "media"), quickCommand("set media volume to 30 percent"))
    assertEquals(volume("up"), quickCommand("turn the volume up"))
    assertEquals(volume("down", "ringtone"), quickCommand("ringtone volume down"))
    assertEquals(volume("max", "alarm"), quickCommand("alarm volume max"))
    assertEquals(volume("mute"), quickCommand("mute"))
    assertEquals(volume("50%"), quickCommand("volume 5"))
  }

  @Test
  fun longerRequestsGoToTheModel() {
    assertNull(quickCommand("silent mode in five minutes"))
    assertNull(quickCommand("put the phone on vibrate until 6"))
    assertNull(quickCommand("volume 50 in 5 minutes"))
    assertNull(quickCommand("why is my phone on silent?"))
    assertNull(quickCommand("silent night by the choir"))
  }

  @Test
  fun parsesNamesAndLevels() {
    assertEquals(SoundStream.MEDIA, streamNamed(null))
    assertEquals(SoundStream.MEDIA, streamNamed("music"))
    assertEquals(SoundStream.RING, streamNamed("ringtone"))
    assertEquals(SoundStream.NOTIFICATION, streamNamed("notifications"))
    assertEquals(SoundStream.ALARM, streamNamed("alarm"))
    assertNull(streamNamed("bananas"))
    assertEquals(RingerMode.SILENT, ringerModeNamed("silent"))
    assertEquals(RingerMode.VIBRATE, ringerModeNamed("vibration"))
    assertEquals(RingerMode.SOUND, ringerModeNamed("normal"))
    assertNull(ringerModeNamed("purple"))
    assertEquals(VolumeChange.Percent(70), volumeChangeFrom("70%"))
    assertEquals(VolumeChange.Percent(50), volumeChangeFrom("half"))
    assertEquals(VolumeChange.Up, volumeChangeFrom("a bit louder"))
    assertEquals(VolumeChange.Mute, volumeChangeFrom("mute"))
    assertEquals(VolumeChange.Percent(40), volumeChangeFrom("unmute"))
    assertNull(volumeChangeFrom("purple"))
  }

  @Test
  fun indexesStayInRange() {
    assertEquals(0, volumeIndexFor(VolumeChange.Percent(0), 7, 0, 15))
    assertEquals(8, volumeIndexFor(VolumeChange.Percent(50), 0, 0, 15))
    assertEquals(15, volumeIndexFor(VolumeChange.Percent(100), 0, 0, 15))
    // A small percentage never rounds to silence.
    assertEquals(1, volumeIndexFor(VolumeChange.Percent(2), 0, 0, 15))
    assertEquals(15, volumeIndexFor(VolumeChange.Up, 15, 0, 15))
    assertEquals(0, volumeIndexFor(VolumeChange.Down, 0, 0, 15))
    assertEquals(1, volumeIndexFor(VolumeChange.Mute, 5, 1, 7))
    assertEquals(50, percentOf(5, 0, 10))
  }
}
