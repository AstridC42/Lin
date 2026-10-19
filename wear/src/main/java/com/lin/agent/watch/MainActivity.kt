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

package com.lin.agent.watch

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.lin.agent.core.wear.WearProtocol
import java.util.Locale

/**
 * Lin on the watch: speak or type a question, and the Lin on the phone answers it here.
 *
 * Asked by voice, the answer is also read aloud, the same rule as on the phone: a spoken
 * question wants a spoken answer, a typed one does not.
 */
class MainActivity : ComponentActivity() {

  private var speech: TextToSpeech? = null

  private val voice =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
      if (!said.isNullOrBlank()) WatchLink.ask(this, said, spoken = true)
    }

  private val typed =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(KEY_TYPED)?.toString()
      if (!text.isNullOrBlank()) WatchLink.ask(this, text, spoken = false)
    }

  private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    speech = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) speech?.language = Locale.getDefault() }
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    setContent { MaterialTheme { WatchScreen(onSpeak = ::speak, onType = ::type, onReadAloud = ::readAloud) } }
    // From the Tile: straight to listening.
    if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true && savedInstanceState == null) speak()
    lastSpoken = ""
    observeSpokenAnswers()
  }

  override fun onResume() {
    super.onResume()
    WatchLink.visible = true
  }

  override fun onPause() {
    WatchLink.visible = false
    super.onPause()
  }

  override fun onDestroy() {
    speech?.shutdown()
    super.onDestroy()
  }

  /** The watch's own speech input; a typed question if this watch has none. */
  private fun speak() {
    val listen =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ask_prompt))
    if (listen.resolveActivity(packageManager) != null) voice.launch(listen) else type()
  }

  /** The watch's input picker: keyboard, handwriting, or voice, whatever the watch offers. */
  private fun type() {
    val input = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(
      input,
      listOf(RemoteInput.Builder(KEY_TYPED).setLabel(getString(R.string.ask_prompt)).build()),
    )
    typed.launch(input)
  }

  private fun readAloud(text: String) {
    speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
  }

  private var lastSpoken = ""

  /** Reads out an answer to a spoken question, once, when it arrives. */
  private fun observeSpokenAnswers() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        WatchLink.state.collect {
          if (it.status == Status.DONE && it.spoken && it.answer.isNotBlank() && it.answer != lastSpoken) {
            lastSpoken = it.answer
            readAloud(it.answer)
          }
        }
      }
    }
  }

  companion object {
    const val EXTRA_LISTEN = "listen"
    private const val KEY_TYPED = "question"
  }
}

@Composable
private fun WatchScreen(onSpeak: () -> Unit, onType: () -> Unit, onReadAloud: (String) -> Unit) {
  val state by WatchLink.state.collectAsStateWithLifecycle()
  val list = rememberScalingLazyListState()
  AppScaffold {
    ScreenScaffold(scrollState = list) {
      ScalingLazyColumn(
        state = list,
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        item {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.lin_logo), contentDescription = null, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
          }
        }
        if (state.question.isNotEmpty()) {
          item {
            Text(
              state.question,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 12.dp),
            )
          }
        }
        when (state.status) {
          Status.SENDING, Status.WORKING ->
            item {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(stringResource(if (state.status == Status.SENDING) R.string.sending else R.string.thinking), style = MaterialTheme.typography.bodySmall)
              }
            }
          Status.DONE ->
            item {
              Text(state.answer, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            }
          Status.NO_PHONE ->
            item { Text(stringResource(R.string.no_phone), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error) }
          Status.NO_ANSWER ->
            item { Text(stringResource(R.string.no_answer), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error) }
          Status.IDLE -> item { Text(stringResource(R.string.hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        }
        item {
          Button(onClick = onSpeak, modifier = Modifier.fillMaxWidth(), icon = {
            androidx.wear.compose.material3.Icon(painterResource(R.drawable.ic_mic), contentDescription = null)
          }) { Text(stringResource(R.string.speak)) }
        }
        item { FilledTonalButton(onClick = onType, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.type)) } }
        if (state.status == Status.DONE) {
          item { FilledTonalButton(onClick = { onReadAloud(state.answer) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_aloud)) } }
        }
      }
    }
  }
}
