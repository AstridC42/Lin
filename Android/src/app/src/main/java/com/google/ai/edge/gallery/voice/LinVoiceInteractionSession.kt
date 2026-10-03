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

package com.google.ai.edge.gallery.voice

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.app.assist.AssistStructure
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.assistant.AssistantSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "AGVoiceSession"

/** Extra set on the launch intent when the assistant was summoned hands-free. */
const val EXTRA_START_LISTENING = "com.lin.assistant.START_LISTENING"

/**
 * One summoning of the assistant.
 *
 * The system creates this when the assist gesture fires, the power button is held, a Bluetooth or
 * steering-wheel voice button is pressed, or another app asks for the assistant. Every one of
 * those is a request that arrives with the phone possibly locked, possibly in a pocket, possibly
 * in a car mount - so it listens straight away rather than waiting for someone to reach for the
 * keyboard.
 *
 * ### Why a panel rather than the app
 *
 * This used to open the chat screen and get out of the way. That works, and it is wrong: holding
 * the power button to ask what time a shop closes should not close the map you were reading. The
 * assistant this replaces answers in a card over whatever is in front, and so does this one - it
 * listens, shows what it heard, and answers in place when the chat screen has a model loaded, which
 * is the only time an answer is possible without a thirty-second wait. When it is not, the panel
 * hands the question to the chat and says that is what it is doing, rather than appearing to think
 * and then producing nothing.
 *
 * ### On wake words
 *
 * There is no supported way for an app like this one to register a custom always-on wake word.
 * `AlwaysOnHotwordDetector`, reachable from a voice interaction service, only recognises keyphrases
 * that the device's own low-power audio DSP has been built with and enrolled - in practice the
 * manufacturer's phrase, not one this app can add. Anything else means keeping the microphone open
 * continuously from a foreground service, which is a different bargain in battery and privacy and
 * is offered separately and explicitly rather than pretended to be a wake word here.
 */
class LinVoiceInteractionSession(private val serviceContext: Context) :
  VoiceInteractionSession(serviceContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  // Created on first use and only then. A session can be shown and dismissed without a word being
  // said - the pop-up is off, or the microphone is not granted - and `by lazy` in the tear-down
  // path would build a speech engine purely in order to shut it down again.
  private var recognizerOrNull: OneShotRecognizer? = null
  private var speakerOrNull: AssistantSpeaker? = null

  private val recognizer: OneShotRecognizer
    get() = recognizerOrNull ?: OneShotRecognizer(serviceContext).also { recognizerOrNull = it }

  private val speaker: AssistantSpeaker
    get() = speakerOrNull ?: AssistantSpeaker(serviceContext).also { speakerOrNull = it }

  private var statusView: TextView? = null
  private var heardView: TextView? = null
  private var replyView: TextView? = null
  private var replyScroll: ScrollView? = null
  private var screenButton: Button? = null

  /** The turn in flight, so a second summon or "Again" does not race the first. */
  private var answering: Job? = null

  private val handler = Handler(Looper.getMainLooper())

  /**
   * Which round of listening is current.
   *
   * The silence cut-off is a timer, and a timer cannot be unscheduled reliably enough to rely on:
   * it can already be running when the recognizer reports back. So each round takes a number and
   * the timer only acts if its own round is still the one in progress. Without that, hearing an
   * answer at three point nine seconds would be followed by the cut-off firing anyway and closing
   * the microphone under the reply being worked out.
   */
  private var listenRound = 0

  /**
   * What is on the screen behind the panel, when the platform offers it.
   *
   * Both are optional and arrive separately, after the session is shown: a screenshot for a model
   * that can see, and the text of the view hierarchy for one that cannot. Whether either arrives
   * at all is the system's decision, not this app's - some devices and some apps withhold both,
   * and a FLAG_SECURE screen always will. So the button that uses them only appears once
   * something has turned up.
   */
  private var screenshot: Bitmap? = null
  private var screenText: String = ""

  override fun onCreateContentView(): View {
    val root = layoutInflater.inflate(R.layout.assist_panel, null)
    statusView = root.findViewById(R.id.assist_status)
    heardView = root.findViewById(R.id.assist_heard)
    replyView = root.findViewById(R.id.assist_reply)
    replyScroll = root.findViewById(R.id.assist_reply_scroll)

    screenButton =
      root.findViewById<Button>(R.id.assist_screen).apply {
        setOnClickListener { askAboutScreen() }
        // Shown only once the platform has offered a screenshot or the screen's text.
        visibility = if (screenshot != null || screenText.isNotEmpty()) View.VISIBLE else View.GONE
      }
    root.findViewById<Button>(R.id.assist_again).setOnClickListener { listen() }
    root.findViewById<Button>(R.id.assist_open).setOnClickListener {
      handOver(query = heardTextOrEmpty(), listen = false)
    }
    root.findViewById<Button>(R.id.assist_close).setOnClickListener { hide() }

    // Tapping the dimmed area outside the card dismisses it, the way every other sheet on the
    // phone does. Without this the only way out is the Close button, which is not where a thumb
    // goes first.
    root.setOnClickListener { hide() }
    root.findViewById<View>(R.id.assist_card).setOnClickListener {}
    return root
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    Log.d(TAG, "Assistant summoned (showFlags=$showFlags)")
    if (!AssistAnswering.popupEnabled(serviceContext)) {
      // The user asked for the whole app instead. Same behaviour this had before the panel existed.
      handOver(query = "", listen = true)
      return
    }
    listen()
  }

  /**
   * Handles a summoning that carried a query, for example a voice command routed by the system
   * rather than spoken into the app.
   */
  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    val query =
      state.assistData?.getString(SearchManager.QUERY).orEmpty().ifEmpty {
        state.assistData?.getString(Intent.EXTRA_TEXT).orEmpty()
      }
    // The text of the app in front, for a model that cannot see the screenshot. Captured whether
    // or not a query came with the summon, since the question usually arrives afterwards.
    screenText = readScreenText(state.assistStructure)
    if (screenText.isNotEmpty() || screenshot != null) showScreenButton()

    if (query.isBlank()) return
    Log.d(TAG, "Assistant summoned with a query")
    if (!AssistAnswering.popupEnabled(serviceContext)) {
      handOver(query = query.trim(), listen = false)
      return
    }
    recognizerOrNull?.cancel()
    answer(query.trim())
  }

  /** The screenshot of whatever was in front, scaled down to something a model can take. */
  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    this.screenshot = screenshot?.let { scaleForModel(it) }
    if (this.screenshot != null) showScreenButton()
  }

  override fun onHide() {
    // Whatever round was in flight is over, so a cut-off still queued must not act on the next one.
    listenRound++
    handler.removeCallbacksAndMessages(null)
    // The microphone and the speech engine both outlive the card unless they are told not to, and
    // a pop-up that keeps listening after it is dismissed is the worst version of this feature.
    recognizerOrNull?.cancel()
    speakerOrNull?.stop()
    answering?.cancel()
    // A screenshot of somebody's banking app is not something to keep a reference to once the
    // panel is gone, and it is several megabytes.
    screenshot = null
    screenText = ""
    super.onHide()
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    recognizerOrNull?.cancel()
    speakerOrNull?.release()
    recognizerOrNull = null
    speakerOrNull = null
    scope.cancel()
    super.onDestroy()
  }

  /**
   * Opens the microphone.
   *
   * @param giveUpAfterMillis how long to wait for someone to start speaking before closing the
   *   microphone again, or null to leave that to the recognizer. Set for the round that follows an
   *   answer: the assistant reopening the microphone every time it finishes a sentence is what
   *   makes a conversation possible without touching the phone, and also what would leave the
   *   microphone open indefinitely in a pocket if nobody replied.
   */
  private fun listen(giveUpAfterMillis: Long? = null) {
    answering?.cancel()
    val round = ++listenRound
    showReply(null)
    if (
      ContextCompat.checkSelfPermission(serviceContext, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      // A permission cannot be requested from here - there is no activity to request it into - so
      // the panel says what is missing rather than failing silently at the microphone.
      setStatus(R.string.assist_panel_answered)
      heardView?.text = serviceContext.getString(R.string.assist_panel_no_microphone)
      return
    }

    setStatus(R.string.assist_panel_listening)
    heardView?.text = serviceContext.getString(R.string.assist_panel_prompt)
    recognizer.start(
      onPartial = { partial ->
        if (partial.isNotBlank()) {
          // Something is being said, so the cut-off has done its job. Whoever is talking gets as
          // long as they need to finish the sentence.
          listenRound++
          heardView?.text = partial
        }
      },
      onFinal = { spoken ->
        listenRound++
        if (spoken.isBlank()) {
          setStatus(R.string.assist_panel_answered)
          heardView?.text = serviceContext.getString(R.string.assist_panel_no_speech)
        } else {
          heardView?.text = spoken
          answer(spoken)
        }
      },
    )

    if (giveUpAfterMillis != null) {
      handler.postDelayed(
        {
          if (listenRound == round) {
            recognizer.cancel()
            setStatus(R.string.assist_panel_answered)
            heardView?.text = serviceContext.getString(R.string.assist_panel_still_there)
          }
        },
        giveUpAfterMillis,
      )
    }
  }

  private fun answer(query: String) {
    answering?.cancel()
    setStatus(R.string.assist_panel_thinking)
    showReply(null)
    answering =
      scope.launch {
        val reply = AssistAnswering.answer(serviceContext, query)
        if (reply == null) {
          // Nothing loaded to answer with. Saying so before the chat opens is the difference
          // between a slow assistant and one that appeared to ignore the question.
          showReply(serviceContext.getString(R.string.assist_panel_opening))
          handOver(query = query, listen = false)
          return@launch
        }
        setStatus(R.string.assist_panel_answered)
        showReply(reply)
        // Always spoken, whatever the chat screen's own setting says. This panel is only ever
        // reached by holding a button and talking, and an answer that has to be read defeats the
        // gesture that asked for it.
        speaker.speak(reply) {
          // Straight back to listening, the way a conversation works - no second button press to
          // say the next thing. Not instantly, though: the phone's own speaker is still settling
          // when the engine reports done, and a microphone opened into that records the assistant
          // and answers itself.
          handler.postDelayed({ listen(giveUpAfterMillis = FOLLOW_UP_SILENCE_MILLIS) }, SETTLE_MILLIS)
        }
      }
  }

  /**
   * Shows the "This screen" button, now that there is something behind the panel to ask about.
   *
   * Hidden until then rather than present and refusing: both the screenshot and the view text are
   * the system's to offer, and on a screen that withholds them - anything marked secure, a banking
   * app, some keyboards - a button that could only apologise is worse than no button.
   */
  private fun showScreenButton() {
    screenButton?.visibility = View.VISIBLE
  }

  /** Asks about what is on the screen, with whatever the platform gave us to go on. */
  private fun askAboutScreen() {
    val shot = screenshot
    val text = screenText
    if (shot == null && text.isEmpty()) return

    heardView?.text = serviceContext.getString(R.string.assist_panel_this_screen_question)
    answering?.cancel()
    setStatus(R.string.assist_panel_thinking)
    showReply(null)
    answering =
      scope.launch {
        // The screenshot goes to a model that can see; the text goes to one that cannot, and is
        // sent alongside either way - it costs little and it is the only thing a text-only model
        // has to work from.
        val question = buildString {
          append(serviceContext.getString(R.string.assist_panel_this_screen_prompt))
          if (text.isNotEmpty()) {
            append("\n\n")
            append(text)
          }
        }
        val reply = AssistAnswering.answer(serviceContext, question, image = shot)
        if (reply == null) {
          showReply(serviceContext.getString(R.string.assist_panel_opening))
          handOver(query = serviceContext.getString(R.string.assist_panel_this_screen_prompt), listen = false)
          return@launch
        }
        setStatus(R.string.assist_panel_answered)
        showReply(reply)
        speaker.speak(reply) {
          handler.postDelayed({ listen(giveUpAfterMillis = FOLLOW_UP_SILENCE_MILLIS) }, SETTLE_MILLIS)
        }
      }
  }

  /**
   * Every piece of text in the app in front, in reading order.
   *
   * Walks the window hierarchies the platform handed over. Depth is bounded because a view tree is
   * not guaranteed to be shallow and this runs on the main thread with a card already on screen.
   */
  private fun readScreenText(structure: AssistStructure?): String {
    if (structure == null) return ""
    val lines = mutableListOf<String>()
    try {
      for (window in 0 until structure.windowNodeCount) {
        collect(structure.getWindowNodeAt(window).rootViewNode, lines, depth = 0)
      }
    } catch (e: Exception) {
      // The structure is cross-process and can be recycled while being read.
      Log.d(TAG, "Could not read the screen's text", e)
    }
    return ScreenText.summarize(lines)
  }

  private fun collect(node: AssistStructure.ViewNode?, into: MutableList<String>, depth: Int) {
    if (node == null || depth > MAX_VIEW_DEPTH || into.size >= MAX_VIEW_LINES) return
    node.text?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
    for (child in 0 until node.childCount) {
      collect(node.getChildAt(child), into, depth + 1)
    }
  }

  /**
   * A screenshot small enough to hand a model.
   *
   * A phone screenshot is a few million pixels; a vision model works from a few hundred thousand
   * and encoding the rest costs time and memory for nothing. Scaled on the long edge so an
   * unfolded tablet and a tall phone both come out sensible.
   */
  private fun scaleForModel(source: Bitmap): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= MAX_SCREENSHOT_EDGE) return source
    val scale = MAX_SCREENSHOT_EDGE.toFloat() / longest
    return try {
      Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
      )
    } catch (e: Exception) {
      Log.d(TAG, "Could not scale the screenshot; using it as it came", e)
      source
    }
  }

  private fun heardTextOrEmpty(): String {
    val placeholder = serviceContext.getString(R.string.assist_panel_prompt)
    val heard = heardView?.text?.toString().orEmpty()
    return if (heard == placeholder) "" else heard
  }

  private fun setStatus(resId: Int) {
    statusView?.setText(resId)
  }

  private fun showReply(text: String?) {
    replyView?.text = text.orEmpty()
    replyScroll?.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
  }

  private companion object {
    /**
     * How long to wait for a reply before closing the microphone again.
     *
     * Long enough to answer a question, short enough that a panel left in a pocket is not still
     * recording a minute later.
     */
    const val FOLLOW_UP_SILENCE_MILLIS = 4_000L

    /** Long enough for the phone's own speaker to stop before the microphone opens. */
    const val SETTLE_MILLIS = 250L

    /** A view tree is not guaranteed shallow, and this walk runs on the main thread. */
    const val MAX_VIEW_DEPTH = 24

    /** Enough lines to cover a screen; the real cap on what is sent is in ScreenText. */
    const val MAX_VIEW_LINES = 400

    /** A vision model works from a few hundred thousand pixels, not a few million. */
    const val MAX_SCREENSHOT_EDGE = 1024
  }

  /** Opens the full chat, carrying [query] if there is one, and closes the panel. */
  private fun handOver(query: String, listen: Boolean) {
    val intent =
      Intent(serviceContext, MainActivity::class.java).apply {
        // The session is not an activity, so a new task is required; and the assistant should
        // come back to whatever conversation is already open rather than stacking a second copy.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action = Intent.ACTION_ASSIST
        if (query.isNotEmpty()) putExtra(SearchManager.QUERY, query)
        if (listen) putExtra(EXTRA_START_LISTENING, true)
      }
    try {
      startAssistantActivity(intent)
    } catch (e: Exception) {
      // startAssistantActivity is refused when the device is locked and the app is not allowed to
      // show over the keyguard. Falling back keeps the summon working; the system then prompts to
      // unlock rather than silently doing nothing.
      Log.w(TAG, "startAssistantActivity refused, falling back to a normal launch", e)
      try {
        serviceContext.startActivity(intent)
      } catch (e2: Exception) {
        Log.e(TAG, "Could not open the assistant", e2)
      }
    }
    hide()
  }
}
