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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/** One thing the user can try, and what it costs them. */
data class RecoveryAction(val label: String, val detail: String, val onClick: () -> Unit)

/**
 * The only screen the app shows when it could not finish starting twice in a row.
 *
 * Deliberately built from nothing. No view model, no navigation graph, no settings store, no
 * model: every one of those is a suspect when start-up is what failed, and a recovery screen that
 * needs the broken thing in order to draw is not a recovery screen. It takes a string and some
 * lambdas.
 *
 * The actions are offered one at a time, least destructive first, rather than as a single "reset
 * everything" button. Clearing the app's storage from Android's own settings is already that
 * button, and it takes the model download with it - several gigabytes over a phone connection.
 * Almost always something smaller is enough, and the user should get to find that out.
 */
@Composable
fun SafeModeScreen(
  report: String,
  failures: Int,
  reachedPhase: String,
  actions: List<RecoveryAction>,
) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  // Each action is offered once. They are destructive and none of them is worth doing twice by
  // accident, and a greyed-out row is also how the user keeps track of what they have tried.
  var used by remember { mutableStateOf(setOf<String>()) }

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      context.getString(R.string.safe_mode_title),
      style = MaterialTheme.typography.titleLarge,
    )
    Text(
      context.getString(R.string.safe_mode_content, failures),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // How far the last launch got, which is the only evidence there is when the report is empty.
    if (reachedPhase.isNotBlank()) {
      Text(
        context.getString(R.string.safe_mode_reached, reachedPhase),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
      )
    }

    if (report.isNotBlank()) {
      Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
          Text(
            report,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp),
          )
        }
      }
      // The report is the only account of what happened - there is no computer attached to run
      // logcat on - and selecting it by dragging is not a realistic way to send it to anybody.
      OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
        Text(context.getString(R.string.assistant_crash_copy))
      }
    } else {
      // A crash with no recorded exception is itself a finding: it means the process was killed
      // without running any Java code, which is what running out of memory looks like.
      Text(
        context.getString(R.string.safe_mode_no_report),
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    Text(
      context.getString(R.string.safe_mode_things_to_try),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = 8.dp),
    )

    for (action in actions) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedButton(
          onClick = {
            used = used + action.label
            action.onClick()
          },
          enabled = action.label !in used,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (action.label in used) context.getString(R.string.safe_mode_done) else action.label)
        }
        Text(
          action.detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
