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

package com.google.ai.edge.gallery.smarthome

import android.util.Log
import com.google.ai.edge.gallery.net.LocalNetwork
import com.google.ai.edge.gallery.proto.SmartHomeSettings
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGHomeAssistant"

/** One controllable thing in Home Assistant. */
data class HomeEntity(
  val entityId: String,
  val name: String,
  val state: String,
  val domain: String,
)

/** What a call to Home Assistant came back with. */
sealed interface HomeOutcome {
  data class Entities(val entities: List<HomeEntity>) : HomeOutcome

  data class Called(val entityId: String, val service: String) : HomeOutcome

  data class Failed(val message: String) : HomeOutcome

  /** No instance configured. Distinct from a failure: nothing was attempted. */
  data object NotConfigured : HomeOutcome
}

/**
 * Talks to a Home Assistant instance over its REST API.
 *
 * Home Assistant is the smart-home integration that is actually open to an app like this one: a
 * base URL and a long-lived access token, which it issues from the user's profile page for exactly
 * this purpose, and a documented HTTP API. Nothing has to be registered with anyone.
 *
 * Written on `HttpURLConnection` for the same reason the SearXNG client is - no new dependency, and
 * the request shapes here are simple enough that one would not earn its place.
 */
class HomeAssistantClient(private val readSettings: () -> SmartHomeSettings) {

  fun isConfigured(): Boolean {
    val settings = readSettings()
    return settings.enabled &&
      settings.baseUrl.isNotBlank() &&
      settings.accessToken.isNotBlank()
  }

  fun baseUrlOrNull(): String? =
    readSettings().baseUrl.takeIf { it.isNotBlank() }?.let(::normalizeBaseUrl)

  /**
   * Everything controllable, optionally filtered by name or area.
   *
   * Home Assistant reports every entity it knows about, including dozens of diagnostic sensors that
   * nobody would ever ask to switch on. Handing all of them to a model buries the lights in noise
   * and eats the context window, so only the domains that can actually be acted on are returned.
   */
  fun listEntities(filter: String = ""): HomeOutcome {
    if (!isConfigured()) return HomeOutcome.NotConfigured
    val settings = readSettings()
    return try {
      val body = get(settings, "/api/states") ?: return HomeOutcome.Failed(unreachable())
      val array = JSONArray(body)
      val entities = mutableListOf<HomeEntity>()
      for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val entityId = item.optString("entity_id")
        if (entityId.isEmpty()) continue
        val domain = entityId.substringBefore('.')
        if (domain !in CONTROLLABLE_DOMAINS) continue
        val name =
          item.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifEmpty {
            entityId.substringAfter('.').replace('_', ' ')
          }
        if (filter.isNotBlank() && !name.contains(filter, ignoreCase = true)) continue
        entities.add(
          HomeEntity(
            entityId = entityId,
            name = name,
            state = item.optString("state"),
            domain = domain,
          )
        )
      }
      HomeOutcome.Entities(entities.sortedBy { it.name })
    } catch (e: Exception) {
      Log.e(TAG, "Could not list entities", e)
      HomeOutcome.Failed(describe(e))
    }
  }

  /**
   * Calls a service on one entity, for example `light.turn_on`.
   *
   * The domain is taken from the entity id rather than asked for separately: they must agree, and
   * deriving it removes a way for the model to get it wrong.
   */
  fun callService(entityId: String, service: String, data: Map<String, Any> = mapOf()): HomeOutcome {
    if (!isConfigured()) return HomeOutcome.NotConfigured
    val settings = readSettings()
    val domain = entityId.substringBefore('.', "")
    if (domain.isEmpty() || !entityId.contains('.')) {
      return HomeOutcome.Failed(
        "\"$entityId\" is not an entity id. They look like light.kitchen - call listSmartHomeDevices first."
      )
    }
    return try {
      val payload = JSONObject().apply {
        put("entity_id", entityId)
        for ((key, value) in data) put(key, value)
      }
      val body =
        post(settings, "/api/services/$domain/$service", payload.toString())
          ?: return HomeOutcome.Failed(unreachable())
      Log.d(TAG, "Called $domain.$service on $entityId: ${body.take(200)}")
      HomeOutcome.Called(entityId = entityId, service = "$domain.$service")
    } catch (e: Exception) {
      Log.e(TAG, "Could not call $service on $entityId", e)
      HomeOutcome.Failed(describe(e))
    }
  }

  private fun get(settings: SmartHomeSettings, path: String): String? =
    request(settings, path, method = "GET", body = null)

  private fun post(settings: SmartHomeSettings, path: String, body: String): String? =
    request(settings, path, method = "POST", body = body)

  private fun request(
    settings: SmartHomeSettings,
    path: String,
    method: String,
    body: String?,
  ): String? {
    val base = normalizeBaseUrl(settings.baseUrl)
    // Cleartext is permitted in the manifest so that a Home Assistant on the home network is
    // reachable at all. This is what keeps that from meaning cleartext to anywhere: every request
    // here carries a long-lived access token in an Authorization header, and that must not cross
    // the open internet in the clear.
    LocalNetwork.cleartextRefusal("$base$path")?.let {
      Log.w(TAG, "Refusing a cleartext request to a host outside the local network")
      return null
    }
    val timeout = (settings.timeoutSeconds.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS) * 1000
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = timeout
      readTimeout = timeout
      setRequestProperty("Authorization", "Bearer ${settings.accessToken}")
      setRequestProperty("Content-Type", "application/json")
      if (body != null) doOutput = true
    }
    return try {
      if (body != null) {
        connection.outputStream.use { it.write(body.toByteArray()) }
      }
      val code = connection.responseCode
      if (code !in 200..299) {
        // The error stream carries Home Assistant's own explanation, which is far more useful
        // than the status code alone - an unknown service names itself there.
        val error =
          connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        Log.w(TAG, "HTTP $code from $path: ${error.take(300)}")
        throw HomeAssistantHttpException(code, error)
      }
      connection.inputStream.bufferedReader().use(BufferedReader::readText)
    } finally {
      connection.disconnect()
    }
  }

  private fun unreachable(): String =
    "Home Assistant did not answer. Check that the address is reachable from this phone."

  /** Turns an exception into something worth saying to the user. */
  private fun describe(e: Exception): String =
    when {
      e is HomeAssistantHttpException && e.code == 401 ->
        "Home Assistant rejected the access token. Create a new long-lived token in its user " +
          "profile and paste it into the app's smart home settings."
      e is HomeAssistantHttpException && e.code == 404 ->
        "Home Assistant does not know that service or entity."
      e is HomeAssistantHttpException -> "Home Assistant returned an error (HTTP ${e.code})."
      e is java.net.SocketTimeoutException ->
        "Home Assistant did not answer in time. It may be asleep or on another network."
      e is java.net.UnknownHostException ->
        "That address could not be found. A local name like homeassistant.local only works on " +
          "the same network as the instance."
      else -> "Could not reach Home Assistant: ${e.message ?: e::class.java.simpleName}"
    }

  private class HomeAssistantHttpException(val code: Int, val body: String) :
    Exception("HTTP $code")

  companion object {
    private const val DEFAULT_TIMEOUT_SECONDS = 10

    /**
     * The domains worth offering. Everything else Home Assistant exposes is a sensor or a
     * diagnostic, which cannot be acted on and would only crowd out the things that can.
     */
    private val CONTROLLABLE_DOMAINS =
      setOf(
        "light",
        "switch",
        "fan",
        "cover",
        "climate",
        "lock",
        "media_player",
        "vacuum",
        "scene",
        "script",
        "input_boolean",
        "humidifier",
        "water_heater",
        "button",
      )

    /** Trims a trailing slash and adds a scheme, so a pasted address works either way. */
    internal fun normalizeBaseUrl(raw: String): String {
      var value = raw.trim().trimEnd('/')
      if (value.isEmpty()) return value
      if (!value.startsWith("http://") && !value.startsWith("https://")) {
        // Local instances are commonly reached over plain http, and guessing https for one would
        // fail in a way that reads as the address being wrong.
        value = "http://$value"
      }
      return value
    }

    /**
     * The service that puts an entity into the state the user asked for.
     *
     * Home Assistant names these per domain, and several do not have turn_on at all: a cover
     * opens, a lock locks, a button is pressed. Calling turn_on on a lock fails with a message
     * about an unknown service, which tells the user nothing about what to say instead.
     */
    internal fun serviceFor(domain: String, on: Boolean): String =
      when (domain) {
        "cover" -> if (on) "open_cover" else "close_cover"
        "lock" -> if (on) "unlock" else "lock"
        "scene" -> "turn_on"
        "script", "button" -> if (domain == "button") "press" else "turn_on"
        "vacuum" -> if (on) "start" else "return_to_base"
        "media_player" -> if (on) "turn_on" else "turn_off"
        else -> if (on) "turn_on" else "turn_off"
      }
  }
}
