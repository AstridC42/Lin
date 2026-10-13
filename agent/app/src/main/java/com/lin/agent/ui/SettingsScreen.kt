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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.core.models.DownloadState
import com.lin.agent.engine.ServerModel
import com.lin.agent.model.IndexState
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.platform.LocalContext
import com.lin.agent.R
import com.lin.agent.engine.Reasoning

/**
 * Settings, which for now is the server address and what the phone can run.
 *
 * The address is a single free-text URL rather than a host and a port, because it has to accept
 * both ways this server gets reached: a LAN address at home, and a Cloudflare tunnel hostname
 * from outside. Those differ in scheme, port and host shape.
 *
 * The header field is here from the start rather than added later, and it is worth saying why: a
 * tunnel puts the server on the public internet, and Ollama has no authentication of its own.
 * Without something in front of it, anyone who learns the hostname can use the GPU and read the
 * prompts. This is where a Cloudflare Access service token or a bearer token goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenMemory: () -> Unit = {},
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.back),
            )
          }
        },
      )
    },
    modifier = Modifier.imePadding(),
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(horizontal = 20.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(R.string.settings_server_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
      )
      Text(
        stringResource(R.string.settings_server_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = state.ollamaUrl,
        onValueChange = viewModel::onUrlChanged,
        label = { Text(stringResource(R.string.settings_server_url)) },
        placeholder = { Text("http://192.168.1.50:11434") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.ollamaAuthHeader,
        onValueChange = viewModel::onAuthHeaderChanged,
        label = { Text(stringResource(R.string.settings_server_header)) },
        placeholder = { Text("Authorization: Bearer ...") },
        // Not single-line, because a Cloudflare Access service token is two headers and this
        // field exists for exactly that case.
        singleLine = false,
        minLines = 1,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        stringResource(R.string.settings_server_header_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = viewModel::save) { Text(stringResource(R.string.settings_save)) }
        if (state.saved) {
          Text(
            stringResource(R.string.settings_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp),
          )
        }
      }

      Text(
        stringResource(R.string.settings_messages_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_messages_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val messagesContext = LocalContext.current
      OutlinedButton(
        onClick = {
          // Notification access cannot be requested - only granted by the user in their own
          // settings, which for this capability is right.
          runCatching {
            messagesContext.startActivity(
              Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_notification_access))
      }

      Text(
        stringResource(R.string.settings_reasoning_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_reasoning_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Card(modifier = Modifier.fillMaxWidth()) {
        Column {
          // Radio rows rather than a slider: there are three of them and each one needs a
          // sentence saying what it costs, which a slider has nowhere to put.
          ReasoningChoice(
            level = Reasoning.OFF,
            label = R.string.settings_reasoning_off,
            detail = R.string.settings_reasoning_off_detail,
            selected = state.reasoning,
            onPick = viewModel::setReasoning,
          )
          ReasoningChoice(
            level = Reasoning.BRIEF,
            label = R.string.settings_reasoning_brief,
            detail = R.string.settings_reasoning_brief_detail,
            selected = state.reasoning,
            onPick = viewModel::setReasoning,
          )
          ReasoningChoice(
            level = Reasoning.THOROUGH,
            label = R.string.settings_reasoning_thorough,
            detail = R.string.settings_reasoning_thorough_detail,
            selected = state.reasoning,
            onPick = viewModel::setReasoning,
          )
        }
      }
      // Said plainly, because the levels mean something different depending on which model is
      // answering and a setting that silently means two things is worse than one that explains.
      if (state.answerOnServer) {
        Text(
          stringResource(R.string.settings_reasoning_unavailable),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        stringResource(R.string.settings_memory_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.settings_digest),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              stringResource(R.string.settings_digest_detail),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = state.digestConversations,
            onCheckedChange = viewModel::setDigestConversations,
          )
        }
      }

      OutlinedButton(
        onClick = onOpenMemory,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      ) {
        Text(stringResource(R.string.settings_open_memory))
      }

      Text(
        stringResource(R.string.settings_voice_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_voice_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.settings_spoken_replies),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              stringResource(R.string.settings_spoken_replies_detail),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = state.spokenReplies, onCheckedChange = viewModel::setSpokenReplies)
        }
      }
      val context = LocalContext.current
      OutlinedButton(
        onClick = {
          // Android gives no way to request the assistant role directly - it is the user's
          // choice to make in their own settings, which is correct. This only takes them there.
          runCatching {
            context.startActivity(
              Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_set_assistant))
      }
      Text(
        stringResource(R.string.settings_assistant_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Text(
        stringResource(R.string.settings_photos_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_photos_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val photoPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          viewModel.indexPhotos(granted)
        }
      when (val indexing = state.photoIndex) {
        is IndexState.Running ->
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (indexing.total > 0) {
              LinearProgressIndicator(
                progress = { indexing.done.toFloat() / indexing.total },
                modifier = Modifier.fillMaxWidth(),
              )
            } else {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                if (indexing.total > 0) {
                  stringResource(
                    R.string.settings_photos_progress,
                    indexing.done,
                    indexing.total,
                  )
                } else stringResource(R.string.settings_photos_preparing),
                style = MaterialTheme.typography.bodySmall,
              )
              TextButton(onClick = viewModel::stopIndexing) {
                Text(stringResource(R.string.settings_photos_stop))
              }
            }
          }
        is IndexState.Failed ->
          Text(
            indexing.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        else -> Unit
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = { photoPermission.launch(photoPermissionName()) },
          enabled = state.photoIndex !is IndexState.Running,
        ) {
          Text(
            stringResource(
              if (state.photosIndexed > 0) R.string.settings_photos_continue
              else R.string.settings_photos_start
            )
          )
        }
        if (state.photosIndexed > 0) {
          TextButton(onClick = viewModel::clearPhotoIndex) {
            Text(stringResource(R.string.settings_photos_clear))
          }
        }
      }
      Text(
        if (state.photosIndexed > 0) {
          stringResource(R.string.settings_photos_indexed, state.photosIndexed)
        } else stringResource(R.string.settings_photos_none),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Text(
        stringResource(R.string.settings_phone_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      // The permission is asked for as the switch goes on, and a refusal leaves it off - a
      // stored "yes" the permission does not back would make the setting lie about what Lin
      // can do, and the policy requires both regardless.
      val callPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          viewModel.setAllowDirectCalls(allow = granted, granted = granted)
        }
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.settings_direct_calls),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              stringResource(
                if (state.allowDirectCalls) R.string.settings_direct_calls_on
                else R.string.settings_direct_calls_off
              ),
              style = MaterialTheme.typography.bodySmall,
              color =
                if (state.allowDirectCalls) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = state.allowDirectCalls,
            onCheckedChange = { wanted ->
              if (wanted) callPermission.launch(Manifest.permission.CALL_PHONE)
              else viewModel.setAllowDirectCalls(allow = false, granted = false)
            },
          )
        }
      }

      Text(
        stringResource(R.string.settings_answer_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_answer_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
          ChoiceRow(
            title = stringResource(R.string.settings_answer_phone),
            detail = stringResource(R.string.settings_answer_phone_detail),
            selected = !state.answerOnServer,
            onPick = { viewModel.setAnswerOnServer(false) },
          )
          ChoiceRow(
            title = stringResource(R.string.settings_answer_server),
            detail = stringResource(R.string.settings_answer_server_detail),
            selected = state.answerOnServer,
            onPick = { viewModel.setAnswerOnServer(true) },
          )
        }
      }

      Text(
        stringResource(R.string.settings_server_models_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_server_models_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ServerModels(
        state = state.serverModels,
        picked = state.serverModel,
        onCheck = viewModel::checkServer,
        onPick = viewModel::pickServerModel,
      )

      Text(
        stringResource(R.string.settings_models_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
      )
      Text(
        stringResource(R.string.settings_device, state.memoryGb, state.socModel.ifEmpty { "?" }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      for (line in state.models) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(line.name, style = MaterialTheme.typography.bodyLarge)
            Text(
              line.detail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val download = line.download) {
              DownloadState.Waiting ->
                // Indeterminate, because nothing is moving yet. Showing a bar stuck at zero is
                // how a queued download gets mistaken for a broken one.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Text(
                      stringResource(R.string.download_waiting),
                      style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { viewModel.cancelDownload(line.model) }) {
                      Text(stringResource(R.string.download_stop))
                    }
                  }
                }
              is DownloadState.Running -> {
                // Determinate where the total is known, which it is from the catalogue. A
                // spinner for a twenty-minute transfer tells the user nothing.
                LinearProgressIndicator(
                  progress = { download.percent / 100f },
                  modifier = Modifier.fillMaxWidth(),
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    stringResource(
                      R.string.download_progress,
                      download.percent,
                      "%.1f".format(download.receivedBytes / 1_000_000_000.0),
                      "%.1f".format(download.totalBytes / 1_000_000_000.0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                  )
                  TextButton(onClick = { viewModel.cancelDownload(line.model) }) {
                    Text(stringResource(R.string.download_stop))
                  }
                }
              }
              is DownloadState.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(
                    download.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                  )
                  // Resuming, not restarting: whatever arrived is still on disk.
                  Button(onClick = { viewModel.download(line.model) }) {
                    Text(stringResource(R.string.download_retry))
                  }
                }
              else ->
                if (line.present) {
                  Text(
                    stringResource(R.string.download_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                } else {
                  Button(onClick = { viewModel.download(line.model) }) {
                    Text(stringResource(R.string.download_start))
                  }
                }
            }
          }
        }
      }
      // Still shown now that downloading works, because copying a file in is the one way to
      // avoid spending two and a half gigabytes of somebody's allowance twice.
      Text(
        stringResource(R.string.settings_models_where),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        state.modelsDirectory,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 32.dp),
      )
    }
  }
}

/**
 * The server's model list, and the choice of one.
 *
 * Every state says what to do next rather than only what happened, because this screen is where
 * somebody finds out their phone cannot reach their server and there is no logcat on a phone. The
 * messages come from the client, which is where the status code and the exception are, and are
 * written as sentences for that reason.
 */
@Composable
private fun ServerModels(
  state: ServerModelsState,
  picked: String,
  onCheck: () -> Unit,
  onPick: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(onClick = onCheck, enabled = state !is ServerModelsState.Checking) {
        Text(
          stringResource(
            if (state is ServerModelsState.Unknown) R.string.settings_server_check
            else R.string.settings_server_recheck
          )
        )
      }
      if (state is ServerModelsState.Found && state.models.isNotEmpty()) {
        Text(
          stringResource(R.string.settings_server_found, state.models.size),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    when (state) {
      ServerModelsState.Unknown ->
        Text(
          stringResource(R.string.settings_server_unknown),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      ServerModelsState.Checking ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Text(
            stringResource(R.string.settings_server_checking),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      is ServerModelsState.Failed ->
        Text(
          state.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      is ServerModelsState.Found ->
        if (state.models.isEmpty()) {
          Text(
            stringResource(R.string.settings_server_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          // A name that is no longer on the server is said out loud rather than silently
          // unselected: the setting is still there, and the user is the one who decides what
          // replaces it.
          if (picked.isNotEmpty() && state.models.none { it.name == picked }) {
            Text(
              stringResource(R.string.settings_server_missing_pick, picked),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              for (model in state.models) {
                ServerModelRow(
                  model = model,
                  selected = model.name == picked,
                  onPick = { onPick(model.name) },
                )
              }
            }
          }
        }
    }
  }
}

@Composable
private fun ServerModelRow(model: ServerModel, selected: Boolean, onPick: () -> Unit) =
  ChoiceRow(title = model.name, detail = describe(model), selected = selected, onPick = onPick)

/** One pickable row: a radio button, a name, and a line about what picking it means. */
@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onPick: () -> Unit) {
  Row(
    // The whole row, not just the button: a radio button is a small target on a phone, and these
    // lists are read with a thumb.
    modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onPick)
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp, end = 8.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (detail.isNotEmpty()) {
        Text(
          detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The line under a server model's name: size, parameter count, quantisation.
 *
 * Assembled from whatever the server actually reported rather than from a format string with
 * placeholders, because any of the three can be missing - an older server does not send the
 * details block - and "20.0 GB · ·" is worse than "20.0 GB".
 */
private fun describe(model: ServerModel): String =
  listOf(
      if (model.sizeBytes > 0) "%.1f GB".format(model.sizeBytes / 1_000_000_000.0) else "",
      model.parameterSize,
      model.quantization,
    )
    .filter { it.isNotEmpty() }
    .joinToString(" · ")

/**
 * The photo permission this Android version actually uses.
 *
 * Three of them across the versions this app supports, and asking for the wrong one is denied
 * silently rather than with an error - which looks exactly like the user refusing.
 */
private fun photoPermissionName(): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    android.Manifest.permission.READ_MEDIA_IMAGES
  } else {
    @Suppress("DEPRECATION") android.Manifest.permission.READ_EXTERNAL_STORAGE
  }

/** One reasoning level, with what it costs underneath its name. */
@Composable
private fun ReasoningChoice(
  level: Reasoning,
  @StringRes label: Int,
  @StringRes detail: Int,
  selected: Reasoning,
  onPick: (Reasoning) -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected == level, onClick = { onPick(level) })
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected == level, onClick = { onPick(level) })
    Column(modifier = Modifier.weight(1f)) {
      Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
      Text(
        stringResource(detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
