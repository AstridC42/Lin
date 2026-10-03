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

package com.google.ai.edge.gallery.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import java.util.Locale

/**
 * Chooses the voice the assistant answers in, and how it speaks.
 *
 * Everything here previews as it is changed rather than only on save, because a voice cannot be
 * chosen from its name. "Male 1" and "Male 2" are the same words; the only way to pick is to hear
 * them, so tapping a row speaks a line in it, and moving a slider speaks the line again at the new
 * setting. Cancelling puts back what was there before, so trying voices is free.
 */
@Composable
fun VoiceSettingsDialog(
  dataStoreRepository: DataStoreRepository,
  speaker: AssistantSpeaker,
  onDismiss: () -> Unit,
) {
  val stored = remember { dataStoreRepository.readAssistantSettings() }

  // Read once, when the dialog opens. The engine has been running since the screen appeared, so
  // by now it has its voices; a device with none falls through to the explanation below.
  val voices = remember {
    val installed = speaker.availableVoices()
    val labels = VoiceCatalog.label(installed.map { it.name })
    installed
      .mapIndexed { index, voice ->
        SpokenVoice(
          id = voice.name,
          label = labels[index],
          languageTag = voice.locale.toLanguageTag(),
          needsNetwork = voice.isNetworkConnectionRequired,
        )
      }
      // The device's own language first: a picker that opens on Icelandic voices because they
      // sorted earlier is a picker that looks broken.
      .sortedWith(
        compareBy(
          { !it.languageTag.startsWith(Locale.getDefault().language, ignoreCase = true) },
          { it.languageTag },
          { it.label },
        )
      )
  }

  var popupEnabled by remember { mutableStateOf(!stored.assistPopupDisabled) }
  var selectedId by remember { mutableStateOf(stored.ttsVoiceName) }
  var rate by remember { mutableFloatStateOf(VoiceCatalog.speedOrDefault(stored.ttsSpeechRate)) }
  var pitch by remember { mutableFloatStateOf(VoiceCatalog.speedOrDefault(stored.ttsPitch)) }

  val sampleText = stringResource(R.string.voice_settings_sample)

  fun preview() {
    speaker.applyPreferences(voiceId = selectedId, rate = rate, pitch = pitch)
    speaker.speak(sampleText)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.voice_settings_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (voices.isEmpty()) {
          Text(
            stringResource(R.string.voice_settings_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyColumn(
            // Tall enough to compare a few voices, short enough to leave the sliders on screen.
            modifier = Modifier.heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            item {
              VoiceRow(
                label = stringResource(R.string.voice_settings_engine_default),
                detail = stringResource(R.string.voice_settings_engine_default_detail),
                selected = selectedId.isEmpty(),
                onClick = {
                  selectedId = ""
                  preview()
                },
              )
            }
            items(voices, key = { it.id }) { voice ->
              VoiceRow(
                label = voice.label,
                detail =
                  if (voice.needsNetwork) {
                    stringResource(R.string.voice_settings_online_voice, voice.languageTag)
                  } else {
                    voice.languageTag
                  },
                selected = selectedId == voice.id,
                onClick = {
                  selectedId = voice.id
                  preview()
                },
              )
            }
          }
        }

        SpeedSlider(
          label = stringResource(R.string.voice_settings_speed),
          value = rate,
          onValueChange = { rate = it },
          onSettled = { preview() },
        )
        SpeedSlider(
          label = stringResource(R.string.voice_settings_pitch),
          value = pitch,
          onValueChange = { pitch = it },
          onSettled = { preview() },
        )

        TextButton(onClick = { preview() }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.voice_settings_try))
        }

        // Lives here rather than with the wake word because it is about what happens after the
        // assistant is summoned, not about what summons it.
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
              stringResource(R.string.voice_settings_popup),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              stringResource(R.string.voice_settings_popup_detail),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = popupEnabled, onCheckedChange = { popupEnabled = it })
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          dataStoreRepository.saveAssistantSettings(
            dataStoreRepository
              .readAssistantSettings()
              .toBuilder()
              .setTtsVoiceName(selectedId)
              .setTtsSpeechRate(rate)
              .setTtsPitch(pitch)
              .setAssistPopupDisabled(!popupEnabled)
              .build()
          )
          speaker.stop()
          speaker.applyPreferences(voiceId = selectedId, rate = rate, pitch = pitch)
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          // Previewing changed the live engine, so cancelling has to put the saved settings back
          // or "cancel" would quietly keep whichever voice was tried last.
          speaker.stop()
          speaker.applyPreferences(
            voiceId = stored.ttsVoiceName,
            rate = stored.ttsSpeechRate,
            pitch = stored.ttsPitch,
          )
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
private fun VoiceRow(
  label: String,
  detail: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(
          if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Column(modifier = Modifier.padding(start = 4.dp)) {
      Text(label, style = MaterialTheme.typography.bodyLarge)
      Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** A slider over [VoiceCatalog.SPEED_RANGE] that previews once the finger lifts, not while it moves. */
@Composable
private fun SpeedSlider(
  label: String,
  value: Float,
  onValueChange: (Float) -> Unit,
  onSettled: () -> Unit,
) {
  Column {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(
        String.format(Locale.US, "%.1f×", value),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      // Speaking on every pixel of a drag would stutter and say nothing useful.
      onValueChangeFinished = onSettled,
      valueRange = VoiceCatalog.SPEED_RANGE,
      steps = 14,
    )
  }
}
