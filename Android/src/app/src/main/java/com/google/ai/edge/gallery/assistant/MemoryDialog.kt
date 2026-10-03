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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.memory.MemoryMaintenance
import com.google.ai.edge.gallery.memory.MemoryRetention
import com.google.ai.edge.gallery.memory.MemoryVaultRepository
import com.google.ai.edge.gallery.memory.NoteMerge
import com.google.ai.edge.gallery.proto.ConversationDigest
import com.google.ai.edge.gallery.proto.MemoryNote
import com.google.ai.edge.gallery.proto.MemoryVault
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * What Lin knows, and where it came from.
 *
 * This screen is the reason it is reasonable to let an assistant write notes about somebody
 * unattended. Most of what is in the vault was inferred by a model from a conversation, at some
 * point nobody was watching, and a note that is subtly wrong is repeated with complete confidence
 * until it is found. So all of it is here, in the order it is used, with a cross beside every
 * line.
 *
 * Both halves of the vault are shown because they explain each other. The notes are what Lin
 * carries into every conversation; the conversation list is where each note came from, and what
 * happens to a conversation after it ends. Seeing "not yet summarised" against yesterday is a
 * truer picture of the thing than a list of facts with no provenance.
 */
@Composable
fun MemoryDialog(
  memoryVault: MemoryVaultRepository,
  onDismiss: () -> Unit,
  onChanged: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val vault by memoryVault.vault.collectAsState(initial = MemoryVault.getDefaultInstance())
  var draft by remember { mutableStateOf("") }

  val now = System.currentTimeMillis()
  // In the order the model is given them, so what is on screen is what Lin actually leads with.
  val warm = remember(vault) { MemoryRetention.forPrompt(vault.noteList, now, maxNotes = 200) }
  val cold = remember(vault) { vault.noteList.filterNot { MemoryRetention.isWarm(it, now) } }
  val digests = remember(vault) { vault.digestList.sortedByDescending { it.endedAtMillis } }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.memory_title)) },
    text = {
      LazyColumn(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        item {
          Text(
            stringResource(
              R.string.memory_explainer,
              MemoryRetention.MINIMUM_WARM_DAYS,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        if (warm.isEmpty() && cold.isEmpty()) {
          item {
            Text(
              stringResource(R.string.memory_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        items(warm, key = { it.id }) { note ->
          NoteRow(
            note = note,
            onForget = {
              scope.launch {
                memoryVault.deleteNote(note.id)
                onChanged()
              }
            },
            onTogglePin = {
              scope.launch {
                memoryVault.setPinned(note.id, !note.pinned)
                onChanged()
              }
            },
          )
        }

        if (cold.isNotEmpty()) {
          item {
            Text(
              stringResource(R.string.memory_cold_heading, cold.size),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          items(cold.take(COLD_SHOWN), key = { it.id }) { note ->
            NoteRow(
              note = note,
              dimmed = true,
              onForget = {
                scope.launch {
                  memoryVault.deleteNote(note.id)
                  onChanged()
                }
              },
              onTogglePin = {
                scope.launch {
                  memoryVault.setPinned(note.id, true)
                  onChanged()
                }
              },
            )
          }
        }

        item {
          OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(NoteMerge.MAX_NOTE_CHARS) },
            label = { Text(stringResource(R.string.memory_add_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          )
        }
        item {
          TextButton(
            onClick = {
              val text = draft
              draft = ""
              scope.launch {
                memoryVault.addUserNote(text = text, nowMillis = System.currentTimeMillis())
                onChanged()
              }
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.memory_add))
          }
        }

        item {
          Text(
            stringResource(R.string.memory_conversations_heading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
          )
        }
        if (digests.isEmpty()) {
          item {
            Text(
              stringResource(R.string.memory_no_conversations),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(digests.take(DIGESTS_SHOWN), key = { it.id }) { digest -> DigestRow(digest) }

        if (vault.pendingCount > 0) {
          item {
            TextButton(
              onClick = { MemoryMaintenance.runNow(context) },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.memory_digest_now, vault.pendingCount))
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
  )
}

@Composable
private fun NoteRow(
  note: MemoryNote,
  onForget: () -> Unit,
  onTogglePin: () -> Unit,
  dimmed: Boolean = false,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      note.text,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f).padding(end = 4.dp),
    )
    IconButton(onClick = onTogglePin, modifier = Modifier.size(36.dp)) {
      Icon(
        Icons.Rounded.PushPin,
        contentDescription = stringResource(R.string.memory_pin),
        tint =
          if (note.pinned) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onForget, modifier = Modifier.size(36.dp)) {
      Icon(
        Icons.Rounded.Close,
        contentDescription = stringResource(R.string.memory_forget),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun DigestRow(digest: ConversationDigest) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(
      "${digest.title.ifBlank { stringResource(R.string.memory_untitled) }} · ${
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(digest.endedAtMillis))
      }",
      style = MaterialTheme.typography.bodyMedium,
    )
    if (digest.summary.isNotBlank()) {
      Text(
        digest.summary,
        style = MaterialTheme.typography.bodySmall,
        // Marked rather than hidden: a placeholder summary read as a real one is how somebody
        // concludes the memory is poor, when in fact it has not been written yet.
        fontStyle = if (digest.extractive) FontStyle.Italic else FontStyle.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Enough to see the shape of what is cooling without turning the dialog into a database browser. */
private const val COLD_SHOWN = 20

private const val DIGESTS_SHOWN = 15
