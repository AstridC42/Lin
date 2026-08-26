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

package com.google.ai.edge.gallery.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.notifications.StarNotificationListener
import com.google.ai.edge.gallery.voice.AssistantRole

/** How wide the manual is when it sits beside the chat. */
val MANUAL_PANEL_WIDTH: Dp = 300.dp

/** One section of the manual. */
private data class ManualSection(val title: String, val lines: List<String>)

/**
 * A reference to what the assistant can do, and the switches that decide whether it can.
 *
 * Everything here was previously either undiscoverable or only written down in the repository. The
 * app grew capabilities faster than it grew any way of finding out about them: web search, device
 * actions, skills, a wake word, an assistant role, a car screen - all real, none visible from the
 * chat, which is why several of them appeared not to exist at all.
 *
 * The setup section is at the top because it is the part that gates the rest. An assistant that
 * has not been made the device's assistant cannot be summoned, and no amount of reading about the
 * assist gesture fixes that; the button asks the system directly.
 */
@Composable
fun AssistantManual(
  onOpenGallery: (() -> Unit)?,
  onOpenWebSearchSettings: () -> Unit,
  onOpenWakeWordSettings: () -> Unit,
  onOpenSmartHomeSettings: () -> Unit,
  onOpenVoiceSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  // Re-read when the screen comes back, since the role is granted in a system dialog outside the
  // app and the answer would otherwise be stale exactly when the user came back to check it.
  var roleHeld by remember { mutableStateOf(AssistantRole.isHeld(context)) }
  var notificationsGranted by remember {
    mutableStateOf(StarNotificationListener.isGranted(context))
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        roleHeld = AssistantRole.isHeld(context)
        notificationsGranted = StarNotificationListener.isGranted(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      stringResource(R.string.manual_title),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )

    // --- Setup ---------------------------------------------------------------------------
    Card(
      colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(
            if (roleHeld) R.string.manual_role_held else R.string.manual_role_not_held
          ),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
        )
        Text(
          stringResource(R.string.manual_role_explainer),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!roleHeld) {
          Button(
            onClick = {
              val request = AssistantRole.requestIntent(context)
              runCatching {
                context.startActivity(request ?: AssistantRole.settingsIntent(context))
              }
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.manual_make_default))
          }
        }
        // Android does not let an app request notification access in a dialog, so the only way
        // to offer it is a button into system settings. Gone once granted, so the panel does not
        // nag about something already done.
        if (!notificationsGranted) {
          OutlinedButton(
            onClick = {
              runCatching { context.startActivity(StarNotificationListener.settingsIntent()) }
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.manual_grant_notifications))
          }
        }
        // Wraps rather than scrolls: on a phone in portrait these do not fit on one line, and a
        // settings button that is simply off the edge of the panel is a setting nobody finds.
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          OutlinedButton(onClick = onOpenWebSearchSettings) {
            Text(stringResource(R.string.web_search_settings_title))
          }
          OutlinedButton(onClick = onOpenWakeWordSettings) {
            Text(stringResource(R.string.wake_word_tile_label))
          }
          OutlinedButton(onClick = onOpenSmartHomeSettings) {
            Text(stringResource(R.string.smart_home_short))
          }
          OutlinedButton(onClick = onOpenVoiceSettings) {
            Text(stringResource(R.string.voice_settings_title))
          }
        }
      }
    }

    for (section in manualSections()) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          section.title,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        for (line in section.lines) {
          Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (onOpenGallery != null) {
      OutlinedButton(onClick = onOpenGallery, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.assistant_open_gallery))
      }
    }
  }
}

@Composable
private fun manualSections(): List<ManualSection> = listOf(
  ManualSection(
    stringResource(R.string.manual_voice_title),
    listOf(
      stringResource(R.string.manual_voice_talk),
      stringResource(R.string.manual_voice_speak),
      stringResource(R.string.manual_voice_handsfree),
      stringResource(R.string.manual_voice_wake),
      stringResource(R.string.manual_voice_popup),
      stringResource(R.string.manual_voice_choose),
    ),
  ),
  ManualSection(
    stringResource(R.string.manual_modes_title),
    listOf(stringResource(R.string.manual_modes_body)),
  ),
  ManualSection(
    stringResource(R.string.manual_device_title),
    listOf(stringResource(R.string.manual_device_body)),
  ),
  ManualSection(
    stringResource(R.string.manual_notifications_title),
    listOf(
      stringResource(R.string.manual_notifications_body),
      stringResource(R.string.manual_media_body),
    ),
  ),
  ManualSection(
    stringResource(R.string.manual_skills_title),
    listOf(stringResource(R.string.manual_skills_body)),
  ),
  ManualSection(
    stringResource(R.string.manual_smart_home_title),
    listOf(stringResource(R.string.manual_smart_home_body)),
  ),
  ManualSection(
    stringResource(R.string.manual_reminders_title),
    listOf(stringResource(R.string.manual_reminders_body)),
  ),
  ManualSection(
    stringResource(R.string.manual_web_title),
    listOf(stringResource(R.string.manual_web_body)),
  ),
  ManualSection(
    stringResource(R.string.manual_car_title),
    listOf(stringResource(R.string.manual_car_body)),
  ),
)

/** The manual as a panel beside the chat, with a divider between the two. */
@Composable
fun AssistantManualPane(
  onOpenGallery: (() -> Unit)?,
  onOpenWebSearchSettings: () -> Unit,
  onOpenWakeWordSettings: () -> Unit,
  onOpenSmartHomeSettings: () -> Unit,
  onOpenVoiceSettings: () -> Unit,
) {
  Surface(
    modifier = Modifier.width(MANUAL_PANEL_WIDTH).fillMaxHeight(),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    AssistantManual(
      onOpenGallery = onOpenGallery,
      onOpenWebSearchSettings = onOpenWebSearchSettings,
      onOpenWakeWordSettings = onOpenWakeWordSettings,
      onOpenSmartHomeSettings = onOpenSmartHomeSettings,
      onOpenVoiceSettings = onOpenVoiceSettings,
      modifier = Modifier.fillMaxHeight(),
    )
  }
}

/**
 * Puts the manual beside the chat when there is room, and out of the way when there is not.
 *
 * This is what a foldable's extra width is now spent on. Centring the conversation and leaving the
 * rest of the screen empty was the previous answer, and it was both invisible on the device it was
 * written for and not worth seeing: unfolding gained nothing. A panel that answers "what can this
 * thing do" is worth the space, and it is the only place several capabilities were ever visible.
 */
@Composable
fun AssistantTwoPane(
  showManual: Boolean,
  manual: @Composable () -> Unit,
  chat: @Composable () -> Unit,
) {
  if (!showManual) {
    AdaptiveWidthContainer { chat() }
    return
  }
  Row(modifier = Modifier.fillMaxSize()) {
    manual()
    VerticalDivider()
    Box(modifier = Modifier.weight(1f).fillMaxHeight()) { chat() }
  }
}

/** The same manual on a phone, where there is no room to keep it beside the conversation. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssistantManualSheet(
  onOpenGallery: (() -> Unit)?,
  onOpenWebSearchSettings: () -> Unit,
  onOpenWakeWordSettings: () -> Unit,
  onOpenSmartHomeSettings: () -> Unit,
  onOpenVoiceSettings: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    AssistantManual(
      onOpenGallery = onOpenGallery,
      onOpenWebSearchSettings = onOpenWebSearchSettings,
      onOpenWakeWordSettings = onOpenWakeWordSettings,
      onOpenSmartHomeSettings = onOpenSmartHomeSettings,
      onOpenVoiceSettings = onOpenVoiceSettings,
    )
  }
}
