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

package com.lin.agent.core.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin.agent.core.diag.CrashReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Settings, with a corruption handler from the first line rather than added after it bit.
 *
 * The app this replaces had nine proto stores and not one of them had a `corruptionHandler`, so an
 * unreadable file threw on every read, for ever. Several of those reads sat on the path before the
 * first frame, which is how one crash became an app that would not open at all.
 *
 * Two ordinary things put a store in that state: a full disk - likely, on a phone that has just
 * taken several gigabytes of model weights - and a process killed mid-write. Neither is exotic and
 * neither should be fatal.
 *
 * So an unreadable file is replaced with defaults, and reads never throw. It is never silent:
 * every reset goes into the report the recovery screen shows, because losing somebody's
 * preferences is a real cost rather than a free fix. Preferences can be set again. An app that
 * will not open cannot be.
 */
private val Context.dataStore by
  preferencesDataStore(
    name = "settings",
    corruptionHandler =
      ReplaceFileCorruptionHandler {
        Log.w(TAG, "Settings could not be read; resetting to defaults", it)
        emptyPreferences()
      },
  )

private const val TAG = "LinSettings"

/**
 * Settings, exposed by name.
 *
 * Deliberately not a generic `read(key, default)` API. That shape leaked `Preferences.Key` out of
 * this module, which meant every caller needed DataStore on its compile classpath and the storage
 * choice stopped being an implementation detail - the compiler said so immediately, which is the
 * boundary doing its job. Named accessors keep DataStore inside `core`, so swapping it for a proto
 * store or a database later touches this file and nothing else.
 */
class SettingsRepository(private val context: Context) {

  /**
   * Where the model server is, as a full base URL. Empty means not configured.
   *
   * A whole URL rather than a host and a port, because it has to accept both ways this server gets
   * reached: `http://192.168.1.50:11434` on the home network and `https://ollama.example.com`
   * through a tunnel from outside. Those differ in scheme, port and host shape, so storing the
   * parts separately would only have to reassemble them.
   */
  val serverUrl: Flow<String> = read(SERVER_URL, "")

  suspend fun setServerUrl(value: String) = write(SERVER_URL, value)

  /**
   * An optional header sent with every request to the server, as `Name: value`.
   *
   * Here from the start rather than added later, because a tunnel puts the server on the public
   * internet and Ollama has no authentication of its own: without something in front of it,
   * anyone who learns the hostname can use the GPU and read the prompts. A Cloudflare Access
   * service token or a bearer token goes here.
   */
  val serverAuthHeader: Flow<String> = read(SERVER_AUTH_HEADER, "")

  suspend fun setServerAuthHeader(value: String) = write(SERVER_AUTH_HEADER, value)

  /**
   * Which downloaded chat model should answer, by name. Empty means let the phone decide.
   *
   * Stored since the model screen was first written and read by nothing until the choice was
   * wired up - see [com.lin.agent.core.models.ModelCatalog.chatModelToUse].
   */
  val chatModelName: Flow<String> = read(CHAT_MODEL_NAME, "")

  suspend fun setChatModelName(value: String) = write(CHAT_MODEL_NAME, value)

  /**
   * Which app plays music, by package name. Empty until the user picks one.
   *
   * No default and no guessing. Somebody with Tidal, Spotify and YouTube Music installed has a
   * service they pay for and two they do not, and an assistant that opens the wrong one - or
   * worse, starts a free tier playing an advert - is not saving them anything. Lin says it
   * needs to be told rather than choosing.
   */
  /**
   * When Lin reads a reply out loud: never, when spoken to, or always. See
   * [com.lin.agent.core.voice.SpeakWhen].
   *
   * Falls back to the old yes/no switch while nothing has been chosen here, so nobody loses
   * the setting they had to a new one they have never seen.
   */
  val speakWhen: Flow<String> =
    // Reading the old key directly rather than the property above it: property initialisers
    // run in declaration order, and spokenReplies is declared further down - referring to it
    // here would combine with a flow that does not exist yet.
    kotlinx.coroutines.flow.combine(read(SPEAK_WHEN, ""), read(SPOKEN_REPLIES, true)) {
      chosen,
      oldSwitch ->
      when {
        chosen.isNotBlank() -> chosen
        oldSwitch -> "WHEN_SPOKEN_TO"
        else -> "NEVER"
      }
    }

  suspend fun setSpeakWhen(value: String) = write(SPEAK_WHEN, value)

  /**
   * Which voice, by the engine's own name for it. Empty means whatever the engine opens with.
   *
   * Stored by name rather than by anything structural, for the same reason the server's model
   * is: the list belongs to the engine and changes when one is installed or removed, so the
   * only durable reference is the name - and a name that no longer resolves falls back rather
   * than failing to speak.
   */
  /**
   * Whether the microphone reopens after Lin has finished speaking.
   *
   * Off. A phone that starts listening again on its own is exactly the behaviour people are
   * right to be wary of, so it is asked for rather than assumed - and it only ever applies to
   * an exchange that was spoken in the first place.
   */
  val voiceChat: Flow<Boolean> = read(VOICE_CHAT, false)

  suspend fun setVoiceChat(value: Boolean) = write(VOICE_CHAT, value)

  val voiceName: Flow<String> = read(VOICE_NAME, "")

  suspend fun setVoiceName(value: String) = write(VOICE_NAME, value)

  /** How fast and how high, as the engine's own multipliers. 1.0 is the engine's normal. */
  val speechRate: Flow<Float> = read(SPEECH_RATE, 1.0f)

  suspend fun setSpeechRate(value: Float) = write(SPEECH_RATE, value)

  val speechPitch: Flow<Float> = read(SPEECH_PITCH, 1.0f)

  suspend fun setSpeechPitch(value: Float) = write(SPEECH_PITCH, value)

  /**
   * Whether Lin may press Send itself in another app, as the name of a policy. See
   * [com.lin.agent.core.screen.TapPolicy].
   *
   * Never, by default, and for the same reason calls are staged in the dialer: a sent message
   * cannot be unsent. The difference is that staging this one is nearly free - the words are
   * in the real app's own box with the real send button under the person's thumb - so the
   * default costs one tap and buys the guarantee that nothing Lin misheard ever left the phone.
   */
  val tapSend: Flow<String> = read(TAP_SEND, "")

  suspend fun setTapSend(value: String) = write(TAP_SEND, value)

  /**
   * Tools the user has switched off, newline-separated. See
   * [com.lin.agent.core.tools.disabledFrom].
   *
   * The *off* list rather than the on list, deliberately: a stored on-list would mean every
   * tool added in a later version arrives switched off for everybody who already has the app,
   * invisibly, and reads as the feature not working.
   */
  val disabledTools: Flow<String> = read(DISABLED_TOOLS, "")

  suspend fun setDisabledTools(value: String) = write(DISABLED_TOOLS, value)

  /**
   * The MCP servers the user has added, as a JSON array. See
   * [com.lin.agent.engine.mcp.serversFrom].
   *
   * JSON rather than a line-based format because an auth header can contain anything,
   * including whatever separator a simpler format would need.
   */
  val mcpServers: Flow<String> = read(MCP_SERVERS, "")

  suspend fun setMcpServers(value: String) = write(MCP_SERVERS, value)

  val musicApp: Flow<String> = read(MUSIC_APP, "")

  suspend fun setMusicApp(value: String) = write(MUSIC_APP, value)

  /**
   * Which of the server's models to send work to, by the name the server uses: `qwen3:32b`.
   *
   * Stored by name rather than by anything structural on purpose. The server's list is the
   * server's to change - somebody pulls a model, removes another - so the only durable reference
   * is the name, and a name that is no longer there has to read as "pick again" rather than as a
   * broken setting. Empty means nothing has been picked.
   */
  val serverModelName: Flow<String> = read(SERVER_MODEL_NAME, "")

  suspend fun setServerModelName(value: String) = write(SERVER_MODEL_NAME, value)

  /**
   * Whether the server answers, rather than the phone.
   *
   * An explicit choice rather than a rule the app applies on its own. Deciding automatically -
   * send the hard ones to the server, keep the easy ones here - is the orchestration this app is
   * eventually for, and it needs a model that can judge "hard" before it can be trusted with the
   * choice. Until then, guessing would mean the user never knowing which model answered or why.
   *
   * Off by default: the phone works with no network, no server and no configuration.
   */
  val answerOnServer: Flow<Boolean> = read(ANSWER_ON_SERVER, false)

  suspend fun setAnswerOnServer(value: Boolean) = write(ANSWER_ON_SERVER, value)

  /**
   * Whether Lin may place a call itself, rather than opening the dialer.
   *
   * Off, and it stays off until somebody turns it on knowing what it does. A call is the one
   * action in this app that cannot be undone, and the model deciding to make one is exactly the
   * case where being wrong is unrecoverable - so the default is that the last tap is a person's.
   *
   * Not sufficient on its own either: the policy requires the CALL_PHONE permission as well,
   * because this flag can outlive a permission revoked in system settings.
   */
  val allowDirectCalls: Flow<Boolean> = read(ALLOW_DIRECT_CALLS, false)

  suspend fun setAllowDirectCalls(value: Boolean) = write(ALLOW_DIRECT_CALLS, value)

  /**
   * Where Lin may ring somebody without the last tap being a person's: nowhere, in the car, or
   * anywhere. See [com.lin.agent.core.car.DialPolicy].
   *
   * Reading falls back to the old yes/no switch while nothing has been chosen here, so somebody
   * who turned direct calls on does not silently lose it to a new setting they have never seen.
   * Writing only ever touches the new key; the old one is left where it is, because a half-
   * migrated pair that disagree is worse than one that is merely stale.
   */
  val dialPolicy: Flow<String> =
    kotlinx.coroutines.flow.combine(read(DIAL_POLICY, ""), allowDirectCalls) { chosen, oldSwitch ->
      when {
        chosen.isNotBlank() -> chosen
        oldSwitch -> "ALWAYS"
        else -> "NEVER"
      }
    }

  suspend fun setDialPolicy(value: String) = write(DIAL_POLICY, value)

  /**
   * Whether the panel may appear over the lock screen.
   *
   * Off, and it has to be: a summoned panel shows the conversation, and over a locked phone
   * that is the conversation on a screen anybody can see. It is here because in a car the phone
   * is locked by definition - Android Auto locks it while projecting - so without this, being
   * summoned while driving opens nothing you can look at.
   */
  val answerWhenLocked: Flow<Boolean> = read(ANSWER_WHEN_LOCKED, false)

  suspend fun setAnswerWhenLocked(value: Boolean) = write(ANSWER_WHEN_LOCKED, value)

  /**
   * Whether the "listen in the car" notification is kept up.
   *
   * It exists because of a platform rule rather than a preference: a microphone foreground
   * service may not be started from the background at all, and none of the usual exemptions
   * cover it - but a service started by somebody tapping a notification is allowed. So the
   * notification is how Lin can start listening in a car without the app being opened.
   */
  val carNotification: Flow<Boolean> = read(CAR_NOTIFICATION, false)

  suspend fun setCarNotification(value: Boolean) = write(CAR_NOTIFICATION, value)

  /**
   * Whether Lin reads its replies out when it was spoken to.
   *
   * On by default, unlike the other switches here, and the reason is the asymmetry: being
   * answered out loud after speaking is what somebody who just spoke expects, while the cost of
   * being wrong is a sentence they can interrupt. The rule in `shouldSpeak` is what keeps this
   * from reading typed conversations aloud.
   */
  val spokenReplies: Flow<Boolean> = read(SPOKEN_REPLIES, true)

  suspend fun setSpokenReplies(value: Boolean) = write(SPOKEN_REPLIES, value)

  /**
   * Whether Lin reads finished conversations for things worth remembering.
   *
   * Off by default, and that is the one default on this screen chosen against convenience. The
   * other switches decide what Lin may do; this one decides what it writes down about somebody
   * without being asked each time, which is a different kind of permission. Everything it
   * writes is visible and deletable under what Lin remembers - but the first time it happens
   * should be because the person said yes, not because they had not read the settings.
   */
  val digestConversations: Flow<Boolean> = read(DIGEST_CONVERSATIONS, false)

  suspend fun setDigestConversations(value: Boolean) = write(DIGEST_CONVERSATIONS, value)

  /**
   * How much the model may think before answering, as the name of a level.
   *
   * A string rather than an enum because this module does not know about the engine's types and
   * should not start: settings are device concerns. The caller maps the name, and an unknown one
   * - a setting written by a newer build, then downgraded - reads as the default rather than
   * throwing on a screen somebody is just trying to open.
   *
   * Off by default. Thinking is generated at the same speed as the answer, so on a phone it is
   * paid for in silence before the first word, and that is not a cost to opt somebody into.
   */
  val reasoning: Flow<String> = read(REASONING, "")

  suspend fun setReasoning(value: String) = write(REASONING, value)

  /**
   * Which kind of server is at [serverUrl], as the name of a kind.
   *
   * A string for the same reason as [reasoning]: this module does not know the engine's types.
   * Empty reads as Ollama, which is what this app shipped with - somebody upgrading has a
   * working server and no stored kind, and should find it still working.
   */
  val serverKind: Flow<String> = read(SERVER_KIND, "")

  suspend fun setServerKind(value: String) = write(SERVER_KIND, value)

  /**
   * Whether to keep a copy of every conversation, for training a model on later.
   *
   * On by default, which is the opposite of the digest and deliberately so. The digest writes
   * notes *about* somebody that then steer later answers, and it was not asked for; this keeps
   * a copy of what they said for them to train on, and it was. A feature somebody requested
   * should not have to be found afterwards.
   *
   * What it means is said on its own screen rather than only here: everything said to Lin is
   * written to this phone in readable text, and a conversation that used a tool carries
   * whatever the tool returned - which can include other people's messages and the contents of
   * documents.
   */
  /**
   * Whether the server model's name was typed rather than picked from the server's list.
   *
   * Worth storing, because it changes what an unanswered model list means. Picked from a list
   * that has since stopped working, the server is suspect; typed in, the user has said the
   * model is there and the list was never the authority.
   */
  val serverModelTyped: Flow<Boolean> = read(SERVER_MODEL_TYPED, false)

  suspend fun setServerModelTyped(value: Boolean) = write(SERVER_MODEL_TYPED, value)

  /**
   * Whether to listen for "Hey Lin" when the app is not open.
   *
   * Off, and of all the defaults in this file this is the one least open to argument: it
   * holds the microphone open indefinitely. It is also the most expensive, because there is
   * no wake-word model for this phrase and the recogniser runs continuously instead.
   */
  val wakeWord: Flow<Boolean> = read(WAKE_WORD, false)

  suspend fun setWakeWord(value: Boolean) = write(WAKE_WORD, value)

  val keepTranscripts: Flow<Boolean> = read(KEEP_TRANSCRIPTS, true)

  suspend fun setKeepTranscripts(value: Boolean) = write(KEEP_TRANSCRIPTS, value)

  /**
   * Reads one setting, with [default] on any failure.
   *
   * `catch` rather than a bare flow: the corruption handler above covers a file that cannot be
   * parsed, and this covers everything else that can make a read fail - a full disk being the
   * likely one on a phone that has just taken gigabytes of weights. The failure is reported and
   * the default served, so a disk problem degrades the app rather than closing it.
   */
  /**
   * Every setting, as JSON, for a backup.
   *
   * Whatever is actually stored rather than a hand-written list of fields. A list would be
   * one more place to forget a setting when adding one, and the symptom - a restore that
   * loses the server address but keeps everything else - is the kind nobody notices until
   * they need it.
   *
   * The API key in the header field goes in here. The backup is a file the user keeps, so
   * that is on balance what they want; it is worth knowing, and the screen says so.
   */
  suspend fun exportAll(): String =
    runCatching {
        val preferences = context.dataStore.data.first()
        val out = JSONObject()
        preferences.asMap().forEach { (key, value) ->
          when (value) {
            is String -> out.put(key.name, value)
            is Boolean -> out.put(key.name, value)
            is Int -> out.put(key.name, value)
            is Long -> out.put(key.name, value)
            is Float -> out.put(key.name, value.toDouble())
            else -> Unit
          }
        }
        out.toString()
      }
      .onFailure { Log.w(TAG, "Could not export settings", it) }
      .getOrDefault("{}")

  /**
   * Puts settings back, replacing what is there.
   *
   * Replacing rather than merging, because these are a deliberate configuration rather than
   * accumulated data: half of an old server setup and half of a new one is a state nobody
   * chose. Unknown keys are kept as they come - a backup from a later build should not lose
   * them on the way through an older one.
   */
  suspend fun importAll(json: String): Int =
    runCatching {
        val parsed = JSONObject(json)
        var applied = 0
        context.dataStore.edit { preferences ->
          parsed.keys().forEach { name ->
            when (val value = parsed.get(name)) {
              is String -> preferences[stringPreferencesKey(name)] = value
              is Boolean -> preferences[booleanPreferencesKey(name)] = value
              is Int -> preferences[intPreferencesKey(name)] = value
              is Long -> preferences[longPreferencesKey(name)] = value
              is Double -> preferences[floatPreferencesKey(name)] = value.toFloat()
              else -> return@forEach
            }
            applied++
          }
        }
        applied
      }
      .onFailure { Log.w(TAG, "Could not restore settings", it) }
      .getOrDefault(0)

  private fun <T> read(key: Preferences.Key<T>, default: T): Flow<T> =
    context.dataStore.data
      .catch { error ->
        Log.w(TAG, "Could not read ${key.name}", error)
        CrashReport.note(
          context = context,
          key = "read-settings",
          text =
            "Your settings could not be read, so defaults are in use for now. This usually means " +
              "the phone has run out of storage space. Nothing has been deleted.",
        )
        emit(emptyPreferences())
      }
      .map { it[key] ?: default }

  /** Writes one setting, recording a failure rather than throwing it at a switch being tapped. */
  private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
    try {
      context.dataStore.edit { it[key] = value }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not write ${key.name}", error)
      CrashReport.note(
        context = context,
        key = "write-settings",
        text =
          "A change to your settings could not be saved. This usually means the phone has run " +
            "out of storage space.",
      )
    }
  }

  private companion object {
    val SERVER_URL = stringPreferencesKey("ollama_url")
    val SERVER_AUTH_HEADER = stringPreferencesKey("ollama_auth_header")
    val CHAT_MODEL_NAME = stringPreferencesKey("chat_model_name")
    val MUSIC_APP = stringPreferencesKey("music_app")
    val TAP_SEND = stringPreferencesKey("tap_send")
    val DISABLED_TOOLS = stringPreferencesKey("disabled_tools")
    val MCP_SERVERS = stringPreferencesKey("mcp_servers")
    val SPEAK_WHEN = stringPreferencesKey("speak_when")
    val VOICE_NAME = stringPreferencesKey("voice_name")
    val VOICE_CHAT = booleanPreferencesKey("voice_chat")
    val SPEECH_RATE = floatPreferencesKey("speech_rate")
    val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
    val DIAL_POLICY = stringPreferencesKey("dial_policy")
    val SERVER_MODEL_NAME = stringPreferencesKey("server_model_name")
    val ANSWER_ON_SERVER = booleanPreferencesKey("answer_on_server")
    val ALLOW_DIRECT_CALLS = booleanPreferencesKey("allow_direct_calls")
    val ANSWER_WHEN_LOCKED = booleanPreferencesKey("answer_when_locked")
    val CAR_NOTIFICATION = booleanPreferencesKey("car_notification")
    val SPOKEN_REPLIES = booleanPreferencesKey("spoken_replies")
    val DIGEST_CONVERSATIONS = booleanPreferencesKey("digest_conversations")
    val REASONING = stringPreferencesKey("reasoning")
    val SERVER_KIND = stringPreferencesKey("server_kind")
    val KEEP_TRANSCRIPTS = booleanPreferencesKey("keep_transcripts")
    val WAKE_WORD = booleanPreferencesKey("wake_word")
    val SERVER_MODEL_TYPED = booleanPreferencesKey("server_model_typed")
  }
}
