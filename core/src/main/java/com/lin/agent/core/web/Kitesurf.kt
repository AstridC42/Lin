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

package com.lin.agent.core.web

import org.json.JSONObject

/**
 * Cloudflare's Kitesurf: a light browser that runs on Cloudflare's network and reads or captures
 * a page for Lin, through the Browser Run "Quick Actions" REST API.
 *
 * Lin reads pages itself first. This is the second try for what plain fetching cannot read
 * (pages built with JavaScript, pages that refuse a phone-shaped client) and the way to see what
 * a page looks like. Everything that has to be right about the wire format lives here, pure, so
 * it is tested without a network.
 */
object Kitesurf {

  /** Cloudflare account ids are 32 hexadecimal characters. */
  fun validAccount(account: String): Boolean = Regex("^[0-9a-fA-F]{32}$").matches(account.trim())

  /** Both a valid account and a token: the only state in which the browser is used. */
  fun configured(account: String, token: String): Boolean = validAccount(account) && token.trim().length >= MIN_TOKEN

  /** The endpoint for one Quick Action, with Kitesurf chosen as the browser. */
  fun endpoint(account: String, action: String): String =
    "https://api.cloudflare.com/client/v4/accounts/${account.trim()}/browser-run/$action?browser=kitesurf"

  fun headers(token: String): Map<String, String> =
    mapOf("Authorization" to "Bearer ${token.trim()}", "Content-Type" to "application/json")

  /** The request body for reading a page as markdown. */
  fun markdownBody(url: String): String = JSONObject().put("url", url).toString()

  /**
   * The request body for a phone-shaped screenshot: narrow and tall, like what is on a screen
   * in somebody's hand, and small enough to send on to a model.
   */
  fun screenshotBody(url: String): String =
    JSONObject()
      .put("url", url)
      .put("viewport", JSONObject().put("width", 412).put("height", 915).put("deviceScaleFactor", 2))
      .toString()

  /**
   * The markdown out of a /markdown reply, or null when the reply is not a success.
   * `{"success": true, "result": "<markdown>"}`.
   */
  fun markdownFrom(body: String): String? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (!json.optBoolean("success", false)) return null
    return json.optString("result").takeIf { it.isNotBlank() }
  }

  /** The reason inside Cloudflare's error envelope (`errors[0].message`), or null. */
  fun errorFrom(body: String): String? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val errors = json.optJSONArray("errors") ?: return null
    return errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
  }

  /** What a status means for the person who set this up, in terms of what to go and change. */
  fun describeStatus(status: Int, reason: String? = null): String {
    val said = if (reason.isNullOrBlank()) "" else " Cloudflare said: $reason"
    return when (status) {
      400 -> "Cloudflare could not make sense of that address (400).$said"
      401,
      403 ->
        "Cloudflare did not accept the token ($status). It needs the \"Browser Run - Edit\" " +
          "permission, and it has to belong to the account whose id is set.$said"
      429 ->
        "Cloudflare's free plan allows one browser request every ten seconds (429). Wait a " +
          "moment and ask again."
      in 500..599 -> "Cloudflare's browser had a problem ($status). Worth trying again.$said"
      else -> "Cloudflare's browser answered with status $status.$said"
    }
  }

  /** The address to hand to the browser: http or https only, anything else is refused. */
  fun pageAddress(asked: String): String? {
    val text = asked.trim()
    val url = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    return url.takeIf { it.contains('.') && !it.contains(' ') }
  }

  private const val MIN_TOKEN = 20
}
