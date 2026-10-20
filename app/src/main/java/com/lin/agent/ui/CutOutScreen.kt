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

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.agent.R
import com.lin.agent.core.models.DownloadState
import com.lin.agent.engine.Attachment
import kotlinx.coroutines.launch

/**
 * Tap something in a photo to cut it out. Full screen, over the conversation, because the photo
 * needs all the room there is to be tapped accurately.
 */
@Composable
fun CutOutScreen(
  photo: Uri,
  onClose: () -> Unit,
  onAsk: (Attachment.Picture) -> Unit,
  /** Select the subject in the middle of the photo straight away, when Lin opened this. */
  pickSubject: Boolean = false,
  viewModel: CutOutViewModel = hiltViewModel(key = photo.toString()),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(state.photo, state.modelReady) {
    if (pickSubject && state.photo != null && state.modelReady && state.taps.isEmpty()) viewModel.tap(0.5f, 0.5f)
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(photo) { viewModel.open(photo) }
  LaunchedEffect(state.message) {
    if (state.message.isNotEmpty()) {
      Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
      viewModel.shown()
    }
  }

  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF101014)).statusBarsPadding().navigationBarsPadding()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onClose) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cutout_close), tint = Color.White)
        }
        Text(
          stringResource(R.string.cutout_title),
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        if (state.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = Color.White)
        if (state.taps.isNotEmpty()) {
          AssistChip(onClick = viewModel::undo, label = { Text(stringResource(R.string.cutout_undo)) }, modifier = Modifier.padding(horizontal = 4.dp))
        }
      }

      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val bitmap = state.photo
        if (bitmap == null) {
          CircularProgressIndicator(color = Color.White)
        } else {
          val density = LocalDensity.current
          val boxW = with(density) { maxWidth.toPx() }
          val boxH = with(density) { maxHeight.toPx() }
          // Where the photo actually sits once fitted, so a tap maps to the right pixel.
          val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
          val shownW = bitmap.width * scale
          val shownH = bitmap.height * scale
          val left = (boxW - shownW) / 2
          val top = (boxH - shownH) / 2
          Box(
            modifier =
              Modifier.fillMaxSize().pointerInput(bitmap, state.modelReady, state.removing) {
                detectTapGestures { at ->
                  val x = (at.x - left) / shownW
                  val y = (at.y - top) / shownH
                  if (x in 0f..1f && y in 0f..1f) viewModel.tap(x, y)
                }
              }
          ) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            state.overlay?.let {
              Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
              state.taps.forEach { tap ->
                val c = Offset(left + tap.x * shownW, top + tap.y * shownH)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = c)
                drawCircle(if (tap.keep) Color(0xFF2E7DF6) else Color(0xFFE5484D), radius = 6.dp.toPx(), center = c)
                drawCircle(Color.Black.copy(alpha = .3f), radius = 9.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
              }
            }
          }
        }
        if (!state.modelReady) ModelCard(state.download, viewModel::getModel)
      }

      // What to do with it, once something is selected; how to start, before.
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          FilterChip(selected = !state.removing, onClick = { viewModel.setRemoving(false) }, label = { Text(stringResource(R.string.cutout_mode_select)) })
          FilterChip(selected = state.removing, onClick = { viewModel.setRemoving(true) }, label = { Text(stringResource(R.string.cutout_mode_remove)) })
          Text(
            stringResource(if (state.selected) R.string.cutout_hint_more else R.string.cutout_hint),
            color = Color.White.copy(alpha = .7f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
          )
        }
        if (state.selected) {
          Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveCutOut, enabled = !state.busy) { Text(stringResource(R.string.cutout_save)) }
            Button(onClick = viewModel::saveBlurred, enabled = !state.busy) { Text(stringResource(R.string.cutout_blur)) }
            Button(onClick = { viewModel.share { context.startActivity(it) } }, enabled = !state.busy) {
              Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
              Text(stringResource(R.string.cutout_share), modifier = Modifier.padding(start = 6.dp))
            }
            Button(
              onClick = {
                scope.launch {
                  viewModel.asPicture()?.let {
                    onAsk(it)
                    onClose()
                  }
                }
              },
              enabled = !state.busy,
            ) {
              Text(stringResource(R.string.cutout_ask))
            }
          }
        }
      }
    }
  }
}

/** Shown over the photo until the model is on the phone. */
@Composable
private fun ModelCard(download: DownloadState, onGet: () -> Unit) {
  Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(24.dp).widthIn(max = 360.dp)) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.cutout_model_title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.cutout_model_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      when (download) {
        is DownloadState.Running -> LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
        DownloadState.Waiting -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is DownloadState.Failed -> {
          Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Button(onClick = onGet) { Text(stringResource(R.string.cutout_model_retry)) }
        }
        else -> Button(onClick = onGet) { Text(stringResource(R.string.cutout_model_get)) }
      }
    }
  }
}
