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

package com.lin.agent.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lin.agent.R

/**
 * Shown when two launches in a row did not reach the screen.
 *
 * Built from a string and a lambda, with no view model, no navigation and no settings store behind
 * it. That is the entire point: when start-up is what failed, every one of those is a suspect, and
 * a recovery screen that needs the broken thing in order to draw is not a recovery screen.
 */
@Composable
fun RecoveryScreen(
  report: String,
  failures: Int,
  reachedPhase: String,
  onStartNormally: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleLarge)
    Text(
      stringResource(R.string.recovery_body, failures),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (reachedPhase.isNotBlank()) {
      Text(
        stringResource(R.string.recovery_reached, reachedPhase),
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
      // Selecting a stack trace by dragging on a phone is not a way to report anything, and this
      // is the only account of the failure that exists.
      OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
        Text(stringResource(R.string.recovery_copy))
      }
    } else {
      // An empty report is itself a finding: no Java code ran on the way down, which is what being
      // killed by the system looks like.
      Text(
        stringResource(R.string.recovery_no_report),
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    Button(onClick = onStartNormally, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.recovery_start_normally))
    }
  }
}
