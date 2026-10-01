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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.memory.MemoryBook
import com.google.ai.edge.gallery.proto.RememberedFact

/**
 * What the assistant remembers, and the only place it can be corrected.
 *
 * This screen is not a convenience. A remembered fact goes into every system prompt and is
 * repeated with complete confidence for as long as it sits there, so a wrong one is worse than
 * none at all - and the model is the thing that wrote most of them. Somewhere to read the list back
 * and delete a line is what makes letting it remember anything reasonable in the first place.
 *
 * Facts can also be added here directly, which is often easier than getting the model to save the
 * right wording by talking to it.
 */
@Composable
fun MemoryDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
  onChanged: () -> Unit,
) {
  var facts by remember {
    mutableStateOf(dataStoreRepository.readAssistantMemory().factList.toList())
  }
  var draft by remember { mutableStateOf("") }

  fun apply(updated: com.google.ai.edge.gallery.proto.AssistantMemory?) {
    if (updated == null) return
    dataStoreRepository.saveAssistantMemory(updated)
    facts = updated.factList.toList()
    onChanged()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.memory_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.memory_explainer, MemoryBook.MAX_FACTS),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (facts.isEmpty()) {
          Text(
            stringResource(R.string.memory_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyColumn(
            modifier = Modifier.heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            items(facts, key = { it.id }) { fact ->
              MemoryRow(
                fact = fact,
                onForget = { apply(MemoryBook.forget(dataStoreRepository.readAssistantMemory(), fact.id)) },
              )
            }
          }
        }

        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it.take(MemoryBook.MAX_FACT_CHARS) },
          label = { Text(stringResource(R.string.memory_add_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
          onClick = {
            apply(
              MemoryBook.remember(
                current = dataStoreRepository.readAssistantMemory(),
                text = draft,
                nowMillis = System.currentTimeMillis(),
              )
            )
            draft = ""
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.memory_add))
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
  )
}

@Composable
private fun MemoryRow(fact: RememberedFact, onForget: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      fact.text,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f).padding(end = 4.dp),
    )
    IconButton(onClick = onForget) {
      Icon(
        Icons.Rounded.Close,
        contentDescription = stringResource(R.string.memory_forget),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
