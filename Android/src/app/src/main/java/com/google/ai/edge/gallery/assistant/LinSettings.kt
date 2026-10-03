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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R

/** One thing that can be changed, and what it is set to now. */
data class SettingsRow(
  val title: String,
  /** What it is set to, shown greyed beside the title. Empty when there is nothing to say. */
  val value: String = "",
  /** Shown under the title when the row needs a sentence of explanation. */
  val detail: String = "",
  /** A tick beside the row, for the things that are either set up or not. */
  val done: Boolean? = null,
  val onClick: () -> Unit,
)

/** A titled run of rows. */
data class SettingsSection(val title: String, val rows: List<SettingsRow>)

/**
 * Everything that can be changed, in one place.
 *
 * Settings had grown into eight dialogs reachable from a row of buttons inside the Guide sheet,
 * which is a reasonable place to put the second one and a poor place to put the eighth: nothing
 * said what existed, the order was the order they were written in, and half of it was only
 * findable by someone who already knew it was there.
 *
 * So this screen is an index rather than a reimplementation. Each row opens the dialog that
 * already owns that setting - those dialogs are written, and in several cases tested, and
 * rewriting them into one screen would be a lot of new code to say the same thing. What the
 * screen adds is the part that was missing: a list of what there is, grouped by what it is for,
 * with each row saying what it is currently set to so the page can be read rather than opened
 * eight times.
 *
 * A full-screen dialog rather than a navigation destination, because it has to be reachable from
 * the chat without disturbing a conversation in progress - the chat's state lives in the
 * composition behind it, and sending the assistant through the nav graph to show a settings list
 * would tear that down and rebuild it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinSettings(sections: List<SettingsSection>, onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties =
      DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Scaffold(
        topBar = {
          TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
              IconButton(onClick = onDismiss) {
                Icon(
                  Icons.AutoMirrored.Rounded.ArrowBack,
                  contentDescription = stringResource(R.string.close),
                )
              }
            },
          )
        }
      ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
          for (section in sections) {
            if (section.rows.isEmpty()) continue
            item(key = "head-${section.title}") {
              Text(
                section.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
              )
            }
            for (row in section.rows) {
              item(key = "${section.title}-${row.title}") { SettingsRowItem(row) }
            }
            item(key = "rule-${section.title}") {
              HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsRowItem(row: SettingsRow) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = row.onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(row.title, style = MaterialTheme.typography.bodyLarge)
      // The detail line is for a row whose consequence is not obvious from its name. Kept to one
      // sentence: a settings list that explains everything is a settings list nobody reads.
      if (row.detail.isNotEmpty()) {
        Text(
          row.detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (row.value.isNotEmpty()) {
      Text(
        row.value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    // Only ever a tick, never a cross. A row that is not set up says so in its value; a red mark
    // against six optional features reads as six things wrong with the app.
    if (row.done == true) {
      Icon(
        Icons.Rounded.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}
