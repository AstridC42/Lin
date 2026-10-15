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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.core.models.CatalogModel
import com.lin.agent.model.ChatBackends
import com.lin.agent.model.ChatReadiness
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.LoopEvent
import com.lin.agent.engine.Attachment
import com.lin.agent.engine.Message
import com.lin.agent.engine.picturesNotSeen
import com.lin.agent.engine.withDocuments
import com.lin.agent.engine.reasoningNamed
import com.lin.agent.engine.ToolRegistry
import com.lin.agent.engine.TurnLoop
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.car.inCar
import com.lin.agent.core.voice.Asked
import com.lin.agent.core.voice.Launch
import com.lin.agent.core.voice.Summon
import com.lin.agent.core.voice.shouldListenOnLaunch
import com.lin.agent.core.voice.speakWhenNamed
import com.lin.agent.core.voice.shouldListenAgain
import com.lin.agent.core.voice.shouldSpeak
import com.lin.agent.model.DelegateTool
import com.lin.agent.voice.Dictation
import com.lin.agent.voice.Heard
import com.lin.agent.voice.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import com.lin.agent.R
import com.lin.agent.core.diag.describeFault
import com.lin.agent.core.tools.disabledFrom
import com.lin.agent.engine.delegationIsWorthIt
import com.lin.agent.core.phone.permissionNeededBy
import android.content.pm.PackageManager
import com.lin.agent.core.calendar.todayLine
import com.lin.agent.core.memory.Digest
import com.lin.agent.core.training.TrainingArchive
import com.lin.agent.core.training.TrainingData
import com.lin.agent.core.training.TrainingTurn
import com.lin.agent.model.ConversationLog
import com.lin.agent.model.DigestWorker
import com.lin.agent.notifications.NotificationRelay
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A tool Lin used on this turn, and how it went. */
data class ToolRun(val name: String, val state: ToolState)

/** The three things that can be true of a tool call, all of which the screen shows. */
enum class ToolState {
  RUNNING,
  DONE,
  FAILED,
}

/** Marks the last still-running call of that name as finished. */
private fun List<ToolRun>.settle(name: String, ok: Boolean): List<ToolRun> {
  val at = indexOfLast { it.name == name && it.state == ToolState.RUNNING }
  if (at < 0) return this
  return toMutableList().also {
    it[at] = ToolRun(name, if (ok) ToolState.DONE else ToolState.FAILED)
  }
}

/** What the screen draws. */
data class ConversationState(
  val messages: List<Message> = emptyList(),
  /** The reply arriving right now, token by token. Empty when nothing is in flight. */
  val streaming: String = "",
  val inProgress: Boolean = false,
  val error: String = "",
  /** Which model is answering, shown so it is never ambiguous. Empty when none is loaded. */
  val backendId: String = "",
  /**
   * Set when there is no model on the device.
   *
   * The screen offers the download rather than a text box. There is no stand-in backend to fall
   * back to - an echo that looks like a reply is worse than a screen that says what is missing.
   */
  val missingModel: CatalogModel? = null,
  /** Set when what answered is not what was asked for - see [ChatReadiness.Ready.notice]. */
  val notice: String = "",
  /** Files and pictures waiting to go with the next message. */
  val attached: List<Attachment> = emptyList(),
  /** The tool running right now, so a long delegation reads as busy rather than stuck. */
  /**
   * What Lin did on this turn, in order, with what became of each.
   *
   * One list rather than a name for the running one and a list of the failed ones. The screen
   * shows all three states - running, done, could not - and two fields that each knew half of
   * that could disagree, which is how a tool that failed while another was starting showed as
   * neither.
   */
  val toolRuns: List<ToolRun> = emptyList(),
  /**
   * The model's working-out for the turn in flight, empty when it is not thinking.
   *
   * Kept only for the current turn and dropped when the answer lands. It is not part of the
   * conversation - what the model considered is not what it said - and keeping a transcript of
   * deliberations that get re-sent, digested or read back aloud is all wrong.
   */
  val thinking: String = "",
  /**
   * Tools that failed during this turn, by name.
   *
   * Shown to the user, not only fed back to the model. A failed tool used to be invisible: the
   * message went into the conversation the model sees, and a 2B model handed "that did not
   * work" will still often say it did - which is how Lin reported remembering a name it had
   * not saved. The model's account of what it did is not evidence that it did it.
   */
  /** Pictures a tool found, shown directly rather than described by a model that cannot see. */
  val shows: List<String> = emptyList(),
  /** True while the microphone is open. Shown, because an open microphone must be visible. */
  val listening: Boolean = false,
  /** Words recognised so far, so speaking does not look like nothing happening. */
  val heard: String = "",
  /**
   * A runtime permission a tool just failed for, so the screen can offer the one button.
   *
   * Here rather than left to the tool's own failure message, because a tool has no window and
   * so cannot ask for anything: the weather tool could only say "it is in settings", which a
   * 2B model relayed as "you can check the weather in your settings". The screen asks instead,
   * and only when the permission really is missing. Empty the rest of the time.
   */
  val needsPermission: String = "",
)

/**
 * One conversation, for as long as the app lives.
 *
 * Not a session scoped to a screen, which is what the previous app had and what forced every
 * capability to register on one conversation at once. Here the conversation is the app's spine and
 * screens are views onto it.
 *
 * [SavedStateHandle] is injected rather than ignored because this has to survive a fold: on a Fold
 * 7 the activity is recreated whenever the device opens or closes, and losing the conversation
 * each time would make the app unusable on its own hardware. Phase 1 persists it properly; this
 * keeps it across configuration changes already.
 */
@HiltViewModel
class ConversationViewModel
@Inject
constructor(
  private val backends: ChatBackends,
  private val dictation: Dictation,
  private val conversationLog: ConversationLog,
  private val work: WorkManager,
  private val relay: NotificationRelay,
  private val archive: TrainingArchive,
  private val attachments: com.lin.agent.model.Attachments,
  @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
  private val tools: com.lin.agent.model.Tools,
  private val mcp: com.lin.agent.mcp.McpConnections,
  private val lastSaidHolder: com.lin.agent.model.LastSaid,
  private val speaker: Speaker,
  private val settings: SettingsRepository,
  private val saved: SavedStateHandle,
) : ViewModel() {

  /**
   * What the orchestrator may do, as it stands right now.
   *
   * Read from [Tools] rather than built here. It used to be assembled in this ViewModel,
   * which was fine while nothing else needed to know what tools existed - but a settings
   * screen for switching them off has to list them, and tools from an MCP server arrive
   * while the app is running. One list, in one place, that both read.
   *
   * Taken once at the start of a turn and held for the whole of it, so a server appearing
   * mid-answer cannot change the tools half way through.
   */
  private val registry
    get() = tools.registry.value

  private val _state = MutableStateFlow(ConversationState(messages = restored()))
  val state: StateFlow<ConversationState> = _state.asStateFlow()

  /**
   * The reply written but not sent.
   *
   * Exposed straight from the relay rather than copied into the screen's state, so there is
   * exactly one answer to "is something waiting to be sent" and the Send button cannot be
   * looking at a stale copy of it.
   */
  val stagedReply = relay.staged

  /**
   * Sends it, or says why not.
   *
   * [NotificationRelay.send] can fail for reasons nobody here controls - the notification was
   * dismissed while the card was on screen, or the app that posted it died and took its pending
   * intent with it. Dropping that answer would leave the card sitting there after a tap, which
   * reads as sent. On a feature whose whole point is that you know exactly what went out, a
   * failure has to be louder than nothing.
   */
  fun sendStagedReply(context: android.content.Context) {
    if (!relay.send(context)) {
      _state.update { it.copy(error = context.getString(R.string.reply_failed)) }
      relay.discard()
    }
  }

  fun discardStagedReply() = relay.discard()

  /**
   * Takes on a file or a picture for the next message.
   *
   * Read here rather than when the message is sent, so a file that cannot be read says so
   * while the person is still looking at the composer - not after they have pressed send and
   * are waiting for an answer about it.
   */
  fun attach(uri: android.net.Uri, asPicture: Boolean, context: android.content.Context) {
    viewModelScope.launch {
      val result =
        if (asPicture) attachments.picture(uri, context) else attachments.document(uri, context)
      result
        .onSuccess { item -> _state.update { it.copy(attached = it.attached + item, error = "") } }
        .onFailure { failure ->
          _state.update { it.copy(error = failure.message ?: "That could not be attached.") }
        }
    }
  }

  fun detach(item: Attachment) = _state.update { it.copy(attached = it.attached - item) }

  /**
   * Ends this conversation and begins another.
   *
   * Added because the half-hour boundary left nobody any way to say "that is finished" - the
   * only way to start again was to put the phone down and wait, which is not a thing to make
   * somebody do. It also ends a conversation that has grown long, which is the cheapest way
   * to get the model's full attention back.
   *
   * The one being left is recorded first, so starting a new one never loses the last.
   */
  fun startNewConversation() {
    saved.get<String>(KEY_CONVERSATION_ID)?.let { captureNow(it) }
    saved[KEY_CONVERSATION_ID] = java.util.UUID.randomUUID().toString()
    saved[KEY_LAST_ACTIVE] = System.currentTimeMillis()
    speaker.stop()
    stopListening()
    _state.update {
      it.copy(
        messages = emptyList(),
        attached = emptyList(),
        streaming = "",
        thinking = "",
        error = "",
        shows = emptyList(),
        toolRuns = emptyList(),
      )
    }
    persist(emptyList())
  }

  /** The microphone, so leaving or sending closes it. */
  private var listening: Job? = null

  /** How the question in flight arrived. Decides whether the answer is spoken. */
  private var asked = Asked.TYPED

  init {
    refreshReadiness()
    // Asked once, in the background, so a server's tools are in the list by the time anybody
    // types. Failures are reported on the settings screen rather than here: a server that is
    // asleep should cost its own tools and nothing else, least of all the first screen.
    viewModelScope.launch { runCatching { mcp.refresh() } }
  }

  /**
   * Acts on a summon, exactly once.
   *
   * Summoned as the assistant means somebody held the power button, or said "Hey Lin", and
   * there is no keyboard in either - so the microphone opens. Said words arrive already spoken
   * and are simply asked: making somebody repeat the question they just asked is slower than
   * not having a wake word at all. Opened from the launcher, neither happens.
   *
   * The guard is the part worth having. The screen's start-up effect runs again whenever the
   * screen is recreated, and on a foldable that is every time the device is opened - so
   * unfolding the phone after a wake-word question used to ask it a second time. [token]
   * identifies the summon rather than the words, so a repeated question still goes and a
   * recreated screen does not. Remembered in saved state, because that is what survives the
   * recreation it has to outlive.
   */
  fun onSummoned(token: String, spoken: String, launch: Launch, microphoneGranted: Boolean) {
    if (Summon.alreadyHandled(saved.get<String>(KEY_SUMMON), token)) return
    saved[KEY_SUMMON] = token
    if (spoken.isNotBlank()) {
      send(spoken, spoken = true)
    } else if (shouldListenOnLaunch(launch, microphoneGranted)) {
      startListening()
    }
  }

  /** Opens the microphone and sends what it hears. */
  fun startListening() {
    if (_state.value.listening) return
    speaker.stop()
    listening?.cancel()
    _state.update { it.copy(listening = true, heard = "", error = "") }
    listening =
      viewModelScope.launch {
        dictation.listen().collect { event ->
          when (event) {
            is Heard.Partial -> _state.update { it.copy(heard = event.text) }
            is Heard.Final -> {
              _state.update { it.copy(listening = false, heard = "") }
              asked = Asked.SPOKEN
              send(event.text, spoken = true)
            }
            is Heard.Failed ->
              _state.update { it.copy(listening = false, heard = "", error = event.message) }
          }
        }
      }
  }

  fun stopListening() {
    listening?.cancel()
    listening = null
    _state.update { it.copy(listening = false, heard = "") }
  }

  /**
   * Re-checks whether a model is available.
   *
   * Called on open and after a download finishes, because the usual sequence is that the app is
   * installed before the model exists - so the conversation has to become possible without a
   * restart.
   */
  fun refreshReadiness(afterDownload: Boolean = false) {
    viewModelScope.launch {
      if (afterDownload) backends.refresh()
      when (val readiness = backends.readiness()) {
        is ChatReadiness.NoModel ->
          _state.update {
            it.copy(missingModel = readiness.suggested, backendId = "", notice = "")
          }
        is ChatReadiness.Ready ->
          _state.update {
            it.copy(
              missingModel = null,
              backendId = readiness.backend.id,
              notice = readiness.notice,
            )
          }
      }
    }
  }

  fun send(text: String, spoken: Boolean = false) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || _state.value.inProgress) return
    asked = if (spoken) Asked.SPOKEN else Asked.TYPED
    // Kept where a tool can reach it. One decision depends on the person's own words rather
    // than on the model's reading of them - whether a message should be sent or only written
    // - and that is an action with no undo. See SendMessageTool.
    rememberWhatWasSaid(trimmed)
    speaker.stop()

    // Before the message is added, because this is what decides whether it joins the previous
    // conversation or begins one - and beginning one clears what came before.
    conversationNow()

    // The user's message goes up immediately, before anything is resolved. Choosing a backend
    // can mean asking the server whether it is there, and a message that only appears once that
    // round trip finishes reads as a Send button that did nothing.
    // Documents are folded into the words; pictures ride along as attachments, because only
    // one of those is something a backend needs to know about. See withDocuments.
    val carried = _state.value.attached
    val outgoing =
      _state.value.messages +
        Message(
          role = Message.Role.USER,
          text = withDocuments(trimmed, carried),
          attachments = carried.filterIsInstance<Attachment.Picture>(),
        )
    _state.update {
      it.copy(
        messages = outgoing,
        attached = emptyList(),
        inProgress = true,
        streaming = "",
        thinking = "",
        error = "",
        toolRuns = emptyList(),
        shows = emptyList(),
      )
    }
    persist(outgoing)

    viewModelScope.launch {
      try {
        val readiness = backends.readiness()
        val backend =
          (readiness as? ChatReadiness.Ready)?.backend
            ?: run {
              _state.update {
                it.copy(
                  inProgress = false,
                  missingModel = (readiness as? ChatReadiness.NoModel)?.suggested,
                  error = "There is no model to answer with yet.",
                )
              }
              return@launch
            }
        // A model with no vision, handed a picture, answers about the question alone and sounds
        // entirely confident. Nothing on screen would suggest it never saw the thing.
        val unseen = picturesNotSeen(carried, backend.capabilities.supportsImages)
        _state.update {
          val notice = (readiness as ChatReadiness.Ready).notice
          it.copy(notice = listOf(notice, unseen).filter { line -> line.isNotBlank() }.joinToString(" "))
        }

        val builder = StringBuilder()
        // Read per turn rather than held, so changing the level applies to the next question
        // instead of the next launch.
        val thinkingBuilder = StringBuilder()
        TurnLoop(
            backend = backend,
            registry = registry,
            role = AgentRole.ORCHESTRATOR,
            systemPrompt = promptForToday(),
            reasoning = reasoningNamed(settings.reasoning.first()),
            // Nothing to hand work to means `delegate` is not offered at all. Left in the
            // list it costs a whole exchange on questions a 2B model was always going to
            // answer itself - it writes a brief, is told there is no server, and then writes
            // the answer - which is twice the wait and a failed tool card for nothing.
            withhold = withheldThisTurn(),
          )
          .run(outgoing)
          .collect { event ->
            when (event) {
              is LoopEvent.Token -> {
                builder.append(event.text)
                _state.update { it.copy(streaming = builder.toString()) }
              }
              // Shown as it arrives, for the same reason tool activity is: thinking is paid for
              // in silence before the first word, and silence is indistinguishable from a hang.
              is LoopEvent.Thought -> {
                thinkingBuilder.append(event.text)
                _state.update { it.copy(thinking = thinkingBuilder.toString()) }
              }
              // Shown while it runs. A delegated task can take a minute on a big model, and
              // silence for a minute is indistinguishable from the app having given up.
              is LoopEvent.ToolStarted ->
                _state.update { it.copy(toolRuns = it.toolRuns + ToolRun(event.name, ToolState.RUNNING)) }
              is LoopEvent.ToolFinished ->
                _state.update {
                  it.copy(
                    // The last running one of that name becomes what it became. Matched by name
                    // rather than by position, because a turn can run two tools at once.
                    toolRuns = it.toolRuns.settle(event.name, event.ok),
                    shows = it.shows + event.shows,
                    // A failure that is only a missing permission is worth one button rather
                    // than a sentence relayed through the model. Kept once set: two tools
                    // failing for the same grant must not clear the offer.
                    needsPermission =
                      it.needsPermission.ifEmpty {
                        if (event.ok) "" else missingGrantFor(event.name)
                      },
                  )
                }
              is LoopEvent.Done -> {
                val complete = outgoing + Message(Message.Role.ASSISTANT, event.text)
                _state.update {
                  // backendId again: it only names the accelerator once the engine has settled on
                  // one, which happens during the first turn rather than when readiness was read.
                  it.copy(
                    messages = complete,
                    streaming = "",
                    thinking = "",
                    inProgress = false,
                    backendId = backend.id,
                  )
                }
                persist(complete)
                // In a car a spoken question gets a spoken answer whatever the switch says -
                // an answer only on screen is no answer to somebody driving.
                val speaking =
                  shouldSpeak(speakWhenNamed(settings.speakWhen.first()), asked, inCar(appContext))
                val carryOn =
                  shouldListenAgain(settings.voiceChat.first(), asked, spoke = speaking)
                if (speaking) {
                  // The microphone reopens from the engine's own "finished" callback rather
                  // than after a guessed delay: opened a moment too early it hears Lin, and a
                  // delay long enough to be safe is long enough to feel broken.
                  speaker.say(event.text, whenDone = { if (carryOn) startListening() })
                }
              }
              is LoopEvent.Failed -> {
                val shown =
                  if (event.detail.isBlank() || event.detail.trim() == event.message.trim()) {
                    event.message
                  } else {
                    "${event.message}\n\n${event.detail}"
                  }
                _state.update {
                  it.copy(
                    streaming = "",
                    thinking = "",
                    inProgress = false,
                    error = shown,
                    backendId = backend.id,
                  )
                }
              }
            }
          }
      } catch (stopped: kotlinx.coroutines.CancellationException) {
        throw stopped
      } catch (failure: Throwable) {
        // A turn must not be able to close the app, and until this one line it could. Every
        // part of answering runs inside this coroutine - reading which backend is ready, the
        // engine itself, every tool the model calls, the speech engine at the end - and an
        // exception from any of them went straight past the ViewModel to the uncaught handler.
        // From the outside that is "the app crashes when I tap that card": no error, no
        // answer, nothing in the conversation, and nothing the person can tell us afterwards.
        //
        // Throwable rather than Exception on purpose. The failure most likely to arrive here
        // is an OutOfMemoryError from loading a model into a phone that has no room for it,
        // and that is an Error - caught nowhere by a `catch (e: Exception)`.
        //
        // Reported, not swallowed. The text goes where errors already go on screen, and a
        // copy goes into the report the recovery screen shows, so the next one of these is
        // something somebody can read back to us rather than a disappearance.
        android.util.Log.e("LinConversation", "The turn failed", failure)
        runCatching {
          com.lin.agent.core.diag.CrashReport.note(
            appContext,
            "turn",
            failure.stackTraceToString(),
          )
        }
        _state.update {
          it.copy(
            streaming = "",
            thinking = "",
            inProgress = false,
            error =
              "Something went wrong part-way through answering: " +
                describe(failure) +
                ". Nothing was lost - ask again.",
          )
        }
      }
    }
  }

  /**
   * The permission [toolName] needed and did not have, or empty.
   *
   * Checked here rather than trusted from the failure, because a tool fails for many reasons
   * and only one of them is fixable by a button. Offering "Allow location" for a weather
   * service that was simply unreachable would be a button that changes nothing.
   */
  private fun missingGrantFor(toolName: String): String {
    val permission = permissionNeededBy(toolName) ?: return ""
    val granted =
      runCatching { appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
        .getOrDefault(true)
    return if (granted) "" else permission
  }

  /** Called once the dialog has been answered, whichever way. */
  fun permissionAnswered() = _state.update { it.copy(needsPermission = "") }

  /**
   * The last thing the person themselves said.
   *
   * Read for one decision and one only: whether sending a message was asked for, as opposed
   * to writing one. That has to come from their words rather than from the model's reading of
   * them - a 2B model asked "did they want it sent?" says yes, because that is the helpful
   * answer, and sending cannot be undone. See
   * [com.lin.agent.core.screen.soundsLikeSendIt].
   */
  private fun rememberWhatWasSaid(text: String) {
    lastSaidHolder.text = text
  }

  /**
   * Tools that exist but must not be offered on this turn.
   *
   * Two reasons, and they are different in kind. `delegate` is withheld whenever handing
   * work over would buy nothing - see [delegationIsWorthIt], which covers both having no
   * server at all and the server already being the one answering. The rest are withheld
   * because the person switched them off, which is their decision and needs no
   * justification from here.
   *
   * Read per turn rather than held, so a switch flipped in settings applies to the next
   * question rather than the next launch.
   */
  private suspend fun withheldThisTurn(): Set<String> {
    val off = disabledFrom(runCatching { settings.disabledTools.first() }.getOrNull())
    val worthIt =
      delegationIsWorthIt(
        hasServerModel = backends.hasServerModel(),
        wantsServerToAnswer = backends.wantsServerToAnswer(),
        serverIsAnswering = backends.serverIsAnswering(),
      )
    return if (worthIt) off else off + DelegateTool.NAME
  }

  /** What to show for a fault nobody designed for. See [describeFault]. */
  private fun describe(failure: Throwable) =
    describeFault(failure::class.simpleName, failure.message)

  fun dismissError() = _state.update { it.copy(error = "") }

  // Saved as two parallel string lists rather than a serialised blob: SavedStateHandle survives a
  // configuration change, and keeping the shape trivial means a format mistake cannot throw during
  // one.
  /**
   * The conversation being had now, starting a new one after a long enough gap.
   *
   * Nothing in this app ends a conversation - the thread simply continues - so there has to be
   * a boundary somewhere for "a conversation" to mean anything at all. Half an hour away is
   * both how people think about it and what keeps the thread from growing past the model's
   * context for ever, which it otherwise does.
   *
   * Starting a new one clears what came before, which is visible: the scrollback goes. That is
   * the cost of the boundary and it buys two things - a corpus of complete conversations rather
   * than thirty prefixes of one, and turns that do not get slower all week.
   */
  private fun conversationNow(): String {
    val now = System.currentTimeMillis()
    val last: Long = saved[KEY_LAST_ACTIVE] ?: 0L
    val existing: String? = saved[KEY_CONVERSATION_ID]

    val id =
      if (existing == null || TrainingData.startsNewConversation(last, now)) {
        // The one that just ended is recorded before it is let go of, so a conversation is never
        // lost by walking away from it for an hour.
        if (existing != null) captureNow(existing)
        val fresh = java.util.UUID.randomUUID().toString()
        saved[KEY_CONVERSATION_ID] = fresh
        _state.update { it.copy(messages = emptyList(), streaming = "", error = "") }
        persist(emptyList())
        fresh
      } else {
        existing
      }
    saved[KEY_LAST_ACTIVE] = now
    return id
  }

  /**
   * Writes the current conversation into the training archive, if that is turned on.
   *
   * Keyed by the conversation's id, so continuing one replaces its record rather than leaving a
   * second partial copy of it - see [com.lin.agent.core.training.TrainingArchive].
   */
  private fun captureNow(conversationId: String) {
    val turns = _state.value.messages.map(::asTrainingTurn)
    viewModelScope.launch {
      if (!settings.keepTranscripts.first()) return@launch
      archive.record(conversationId, turns)
      archive.packIfReady(currentId = saved[KEY_CONVERSATION_ID])
    }
  }

  private fun asTrainingTurn(message: Message) =
    TrainingTurn(
      role =
        when (message.role) {
          Message.Role.USER -> TrainingTurn.Role.USER
          Message.Role.ASSISTANT -> TrainingTurn.Role.ASSISTANT
          Message.Role.SYSTEM -> TrainingTurn.Role.SYSTEM
          Message.Role.TOOL -> TrainingTurn.Role.TOOL
        },
      text = message.text,
      toolName = message.toolName,
    )

  /**
   * The orchestrator prompt with today's date in front of it.
   *
   * Without this the model has no idea what day it is, which makes "put it in the calendar
   * tomorrow" impossible to get right - it was guessing, and a 2B model's guess at the date is
   * a year out as often as not.
   *
   * Day resolution, and that is load-bearing rather than lazy. The system prompt is part of
   * what decides which conversation a turn can be sent to on-device, so a prompt carrying the
   * time of day would build a fresh conversation every minute and throw the history away with
   * it. The date changes once a day, which costs one rebuild.
   */
  private fun promptForToday(): String =
    todayLine(System.currentTimeMillis()) +
      " This is the real date; use it and never a date you remember from elsewhere. " +
      ORCHESTRATOR_PROMPT

  private fun persist(messages: List<Message>) {
    saved[KEY_ROLES] = ArrayList(messages.map { it.role.name })
    saved[KEY_TEXTS] = ArrayList(messages.map { it.text })
  }

  private fun restored(): List<Message> {
    val roles: ArrayList<String> = saved[KEY_ROLES] ?: return emptyList()
    val texts: ArrayList<String> = saved[KEY_TEXTS] ?: return emptyList()
    if (roles.size != texts.size) return emptyList()
    return roles.indices.mapNotNull { i ->
      val role = Message.Role.entries.firstOrNull { it.name == roles[i] } ?: return@mapNotNull null
      Message(role, texts[i])
    }
  }

  /**
   * Called when the screen goes away, so a finished conversation can be read for memories.
   *
   * Here rather than in `onCleared`, which does not run when the app is merely backgrounded -
   * and backgrounded is what "the conversation is over" usually looks like. Enqueued rather
   * than run: the model takes long enough that doing it on the way out would hold the app
   * open, and nothing is waiting for the result.
   */
  fun onStopped() {
    val messages = _state.value.messages

    // The training copy first, and on its own terms. It is a different feature with a different
    // switch and a much lower bar - a two-message exchange is a perfectly good training
    // example, while it is nothing to write a memory about - so it must not sit behind the
    // digest's threshold or the digest's setting.
    saved.get<String>(KEY_CONVERSATION_ID)?.let { captureNow(it) }

    if (messages.size < Digest.MIN_MESSAGES) return
    viewModelScope.launch {
      if (!settings.digestConversations.first()) return@launch
      conversationLog.write(messages)
      work.enqueueUniqueWork(
        DIGEST_WORK,
        // Replace: a newer conversation supersedes an older one that has not been read yet,
        // and the log holds one at a time anyway.
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<DigestWorker>()
          .setConstraints(
            // Nothing waits on this, so it can wait for the phone to be idle-ish. No network
            // constraint: the model it uses is on the phone.
            Constraints.Builder()
              .setRequiresBatteryNotLow(true)
              .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
              .build()
          )
          .build(),
      )
    }
  }

  override fun onCleared() {
    // The microphone and the voice both stop with the screen. Neither is something to leave
    // running because a view model was discarded.
    stopListening()
    speaker.stop()
    super.onCleared()
  }

  private companion object {
    /**
     * Short, and that is the design rather than an omission.
     *
     * The app this replaces carried a prompt long enough to crowd out the conversation, because
     * forty-four tools each needed explaining. An orchestrator with one tool needs a sentence
     * about when to use it and a sentence about what not to lose.
     */
    const val ORCHESTRATOR_PROMPT =
      "You are Lin, an assistant running on this phone. Answer directly whenever you can. " +
        "For work that needs a much larger model - real research, long writing, heavy analysis " +
        "- call delegate. When you do, copy every condition the user stated into constraints, " +
        "word for word; never invent one they did not say. " +
        "To ring someone or text them, call phone_action; it opens the dialer or the composer " +
        "for the user to confirm, so say what you have put on screen rather than claiming it " +
        "is done. Never invent a phone number - pass the name instead. " +
        "Call remember when the user says to keep something, and recall when they refer to " +
        "something you were not told in this conversation. If recall finds nothing, say so " +
        "rather than filling the gap. " +
        "Use find_photo to look through the user\'s pictures; they appear on screen. Set its " +
        "look argument when the question is about what is IN a photo - the best match is then " +
        "shown to you and you may describe it. Without it you have not seen them, so never " +
        "describe what is in one. " +
        "reply_to_message writes a reply onto the screen for the user to send; it does " +
        "not send, so read the wording back and never say a message has gone. " +
        "Use whats_on to look at the calendar rather than guessing what the user has on. " +
        "add_to_calendar opens the calendar filled in and the user saves it, so say it is " +
        "ready rather than saying it is in their calendar; work the date out from today's " +
        "date above and pass it as numbers. A timer is set straight away, but an alarm opens " +
        "the clock to be confirmed - do not say an alarm is set. " +
        "Use media to see or change what is playing; check with action 'what' before " +
        "saying what is on. play_music starts something new in the user\'s own music app - " +
        "pass what they said, such as 'Stan by Eminem'; read its result before answering, " +
        "because it says whether the music actually started or only a search opened. " +
        "open_app opens an app by name. " +
        "phone_setting does the flashlight and Do Not Disturb outright; for wi-fi, bluetooth, " +
        "NFC and airplane mode Android allows no app to switch them, so it opens the phone\'s " +
        "own panel - say it is ready to tap and never say the setting was changed. " +
        "weather answers what it is doing outside - never answer that from your own " +
        "knowledge, which is months old and somewhere else. " +
        "These tools are everything Lin can do to the phone: if something is not on this " +
        "list, say you cannot do it rather than describing how the user could."

    const val DIGEST_WORK = "digest"

    const val KEY_ROLES = "conversation_roles"
    const val KEY_TEXTS = "conversation_texts"
    const val KEY_CONVERSATION_ID = "conversation_id"
    const val KEY_LAST_ACTIVE = "conversation_last_active"
    const val KEY_SUMMON = "conversation_summon"
  }
}
