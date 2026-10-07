/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.assistant.AssistantIntents
import com.google.ai.edge.gallery.assistant.AssistantLaunchRequest
import com.google.ai.edge.gallery.assistant.CrashReport
import com.google.ai.edge.gallery.assistant.RecoveryAction
import com.google.ai.edge.gallery.assistant.SafeMode
import com.google.ai.edge.gallery.assistant.SafeModeScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private var splashScreenAboutToExit: Boolean = false
  private var contentSet: Boolean = false

  /**
   * What the launching intent asked the assistant to do.
   *
   * Kept as Compose state rather than read straight off `getIntent()` so that a second assist
   * gesture, or a share while the app is already open, reaches the assistant screen through
   * [onNewIntent] instead of being ignored.
   */
  private var assistantRequest by mutableStateOf(AssistantLaunchRequest())

  override fun onCreate(savedInstanceState: Bundle?) {
    // We intentionally pass null to discard the saved instance state bundle.
    // This prevents Jetpack Compose from automatically restoring the previous screen
    // and forces the app to start cleanly on the Home Screen after an OS kill.
    super.onCreate(null)

    // Debug: Dump all intent extras to see what FCM unloads
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "onCreate Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }

    // A recovery launch stops here. Nothing below is touched: not the view model, not the
    // allowlist, not the splash screen handshake - all of which read from disk, and any of which
    // could be what the last two launches died in.
    if (SafeMode.active) {
      showSafeMode()
      return
    }

    SafeMode.mark(this, "activity created")
    assistantRequest = AssistantIntents.parse(intent)

    // Convert FCM Console data extras to intent data for GalleryNavGraph to pick up
    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "onCreate: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }

    fun setContent() {
      if (contentSet) {
        return
      }

      SafeMode.mark(this, "content set")
      setContent {
        GalleryTheme {
          Surface(modifier = Modifier.fillMaxSize()) {
            GalleryApp(
              modelManagerViewModel = modelManagerViewModel,
              initialAssistantQuery = assistantRequest.query.ifEmpty { null },
              initialAssistantImageUris = assistantRequest.imageUris,
              startAssistantListening = assistantRequest.startListening,
            )

            // Fade out a "mask" that has the same color as the background of the splash screen
            // to reveal the actual app content.
            var startMaskFadeout by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
              startMaskFadeout = true
              // This launch reached the screen, so it counts as having worked. Recorded from
              // inside the composition rather than at the end of onCreate, because onCreate
              // returning only means the work was scheduled - a crash in the first composition
              // would otherwise be recorded as a success and never trigger safe mode.
              SafeMode.mark(this@MainActivity, "first frame")
              SafeMode.noteLaunchSucceeded(this@MainActivity)
            }
            AnimatedVisibility(
              !startMaskFadeout,
              enter = fadeIn(animationSpec = snap(0)),
              exit =
                fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
            ) {
              Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
              )
            }
          }
        }
      }

      @OptIn(ExperimentalApi::class)
      ExperimentalFlags.enableBenchmark = false

      contentSet = true
    }

    // Two marks, because the view model is constructed lazily on this first access and reads
    // from disk while it is - so "view model created" failing and "model list requested" failing
    // are different faults in different code.
    SafeMode.mark(this, "view model created")
    modelManagerViewModel.loadModelAllowlist()
    SafeMode.mark(this, "model list requested")

    // Show splash screen.
    val splashScreen = installSplashScreen()

    // Set the content when the system-provided splash screen is not shown.
    //
    // This is necessary on some Android versions where the splash screen is optimized away (e.g.,
    // after a force-quit) to ensure the main content is displayed immediately and correctly.
    lifecycleScope.launch {
      delay(1000)
      if (!splashScreenAboutToExit) {
        setContent()
      }
    }

    // Cross-fade transition from the splash screen to the main content.
    //
    // The logic performs the following key actions:
    // 1. Synchronizes Timing: It calculates the remaining duration of the default icon
    //    animation. It then delays its own animations to ensure the custom fade-out begins just
    //    before the original icon animation would have finished.
    // 2. Initiates a cross-fade:
    //    - Fade out the splash screen.
    //    - Fade in the main content.
    // 3. Cleans up: An `onEnd` listener on the fade-out animator calls
    //    `splashScreenView.remove()` to properly remove the splash screen from the view hierarchy
    //    once it's fully transparent.
    splashScreen.setOnExitAnimationListener { splashScreenView ->
      splashScreenAboutToExit = true

      val now = System.currentTimeMillis()
      val iconAnimationStartMs = splashScreenView.iconAnimationStartMillis
      val duration = splashScreenView.iconAnimationDurationMillis
      val fadeOut = ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f)
      fadeOut.interpolator = DecelerateInterpolator()
      fadeOut.duration = 300L
      fadeOut.doOnEnd { splashScreenView.remove() }
      lifecycleScope.launch {
        val setContentDelay = duration - (now - iconAnimationStartMs) - 300
        if (setContentDelay > 0) {
          delay(setContentDelay)
        }
        setContent()
        fadeOut.start()
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  /**
   * Shows the recovery screen, with the few things that might clear whatever is wrong.
   *
   * The actions delete files directly rather than going through the repositories. That is the
   * point: in safe mode none of those stores has been opened, so the files are not held by a
   * DataStore and can simply be removed - and if one of them is the reason the app will not
   * start, going through the code that reads it would be going through the fault.
   *
   * Ordered by what they cost. Settings and memory can be set up again in minutes. Re-downloading
   * a model is gigabytes over a phone connection, so clearing the app's storage - which is what
   * Android's own settings offer - stays the last resort it should be, and is not offered here.
   */
  private fun showSafeMode() {
    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          SafeModeScreen(
            report = CrashReport.read(this).orEmpty(),
            failures = SafeMode.consecutiveFailures,
            reachedPhase = SafeMode.lastMilestone(this),
            actions =
              listOf(
                RecoveryAction(
                  label = getString(R.string.safe_mode_forget_model),
                  detail = getString(R.string.safe_mode_forget_model_detail),
                  onClick = { deleteAppFile("datastore/settings.pb") },
                ),
                RecoveryAction(
                  label = getString(R.string.safe_mode_clear_memory),
                  detail = getString(R.string.safe_mode_clear_memory_detail),
                  onClick = {
                    deleteAppFile("datastore/memory_vault.pb")
                    filesDir.resolve("memory/archive").deleteRecursively()
                  },
                ),
                RecoveryAction(
                  label = getString(R.string.safe_mode_clear_skills),
                  detail = getString(R.string.safe_mode_clear_skills_detail),
                  onClick = {
                    deleteAppFile("datastore/skills.pb")
                    deleteAppFile("datastore/mcp_servers.pb")
                  },
                ),
                RecoveryAction(
                  label = getString(R.string.safe_mode_start_normally),
                  detail = getString(R.string.safe_mode_start_normally_detail),
                  onClick = {
                    CrashReport.clear(this)
                    SafeMode.clearFailures(this)
                    // Ends the process rather than navigating. A normal start has to begin from
                    // Application.onCreate, and this one skipped all of it.
                    finishAffinity()
                    Runtime.getRuntime().exit(0)
                  },
                ),
              ),
          )
        }
      }
    }
  }

  /**
   * Deletes one file under the app's files directory, if it is there.
   *
   * Paths are relative to `filesDir`, which is where `Context.dataStoreFile` puts a proto store -
   * `filesDir/datastore/<name>.pb`. Worth stating because resolving these against the parent of
   * `filesDir` compiles, runs, finds nothing and reports success, which is a recovery action that
   * silently does not recover.
   */
  private fun deleteAppFile(relativePath: String) {
    try {
      val target = filesDir.resolve(relativePath)
      if (target.exists()) {
        Log.w(TAG, "Safe mode: deleting $relativePath")
        target.delete()
      }
      // DataStore keeps its temporary file beside the real one, and leaving that behind is how a
      // deleted store comes back in the state it was deleted for.
      filesDir.resolve("$relativePath.tmp").takeIf { it.exists() }?.delete()
    } catch (e: Throwable) {
      Log.e(TAG, "Safe mode: could not delete $relativePath", e)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)

    if (AssistantIntents.isAssistantIntent(intent)) {
      assistantRequest = AssistantIntents.parse(intent)
    }

    // Debug: Dump all intent extras to see what FCM unloads
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "onNewIntent Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }

    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "onNewIntent: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }
  }

  override fun onResume() {
    super.onResume()

    firebaseAnalytics?.logEvent(
      FirebaseAnalytics.Event.APP_OPEN,
      bundleOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "app_platform" to "Android",
        "os_version" to Build.VERSION.SDK_INT.toString(),
        "device_model" to Build.MODEL,
      ),
    )
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
