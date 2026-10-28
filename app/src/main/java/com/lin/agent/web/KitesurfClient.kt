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

package com.lin.agent.web

import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.web.Kitesurf
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What came back from Cloudflare's browser: the result, or a sentence about why not. */
sealed interface BrowserResult<out T> {
  data class Got<T>(val value: T) : BrowserResult<T>

  data class Failed(val reason: String) : BrowserResult<Nothing>
}

/**
 * Cloudflare's Kitesurf browser, through the Browser Run REST API. Used only when the account id
 * and token are set; see [Kitesurf]. Every call sends the address being read to Cloudflare, which
 * is why the settings screen says so.
 */
@Singleton
class KitesurfClient @Inject constructor(private val settings: SettingsRepository) {

  suspend fun available(): Boolean = credentials() != null

  private suspend fun credentials(): Pair<String, String>? {
    val account = runCatching { settings.cloudflareAccount.first() }.getOrDefault("")
    val token = runCatching { settings.cloudflareToken.first() }.getOrDefault("")
    return if (Kitesurf.configured(account, token)) account.trim() to token.trim() else null
  }

  /** The page as markdown. */
  suspend fun markdown(url: String): BrowserResult<String> {
    val (account, token) = credentials() ?: return BrowserResult.Failed(NOT_SET)
    val reply = post(Kitesurf.endpoint(account, "markdown"), token, Kitesurf.markdownBody(url))
    if (reply.status !in 200..299) return BrowserResult.Failed(Kitesurf.describeStatus(reply.status, Kitesurf.errorFrom(reply.text())))
    return Kitesurf.markdownFrom(reply.text())?.let { BrowserResult.Got(it) }
      ?: BrowserResult.Failed("Cloudflare's browser opened the page but found no text on it.")
  }

  /** A phone-shaped screenshot of the page, as the image bytes Cloudflare sent. */
  suspend fun screenshot(url: String): BrowserResult<ByteArray> {
    val (account, token) = credentials() ?: return BrowserResult.Failed(NOT_SET)
    val reply = post(Kitesurf.endpoint(account, "screenshot"), token, Kitesurf.screenshotBody(url))
    if (reply.status !in 200..299) return BrowserResult.Failed(Kitesurf.describeStatus(reply.status, Kitesurf.errorFrom(reply.text())))
    // A success is the image itself; a JSON body here is an error envelope that came with a 200.
    if (reply.bytes.isEmpty() || reply.bytes[0] == '{'.code.toByte()) {
      return BrowserResult.Failed(Kitesurf.errorFrom(reply.text()) ?: "Cloudflare's browser sent no picture.")
    }
    return BrowserResult.Got(reply.bytes)
  }

  private class Reply(val status: Int, val bytes: ByteArray) {
    fun text() = bytes.toString(Charsets.UTF_8)
  }

  private suspend fun post(endpoint: String, token: String, body: String): Reply =
    withContext(Dispatchers.IO) {
      runCatching {
          val connection =
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
              requestMethod = "POST"
              doOutput = true
              connectTimeout = CONNECT_MS
              // A browser has to open the page and wait for it; Cloudflare itself stops at 60 s.
              readTimeout = READ_MS
              Kitesurf.headers(token).forEach { (name, value) -> setRequestProperty(name, value) }
            }
          try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Reply(status, stream?.use { it.readBytes() } ?: ByteArray(0))
          } finally {
            connection.disconnect()
          }
        }
        .getOrElse { Reply(0, (it.message ?: it::class.java.simpleName).toByteArray()) }
    }

  private companion object {
    const val CONNECT_MS = 10_000
    const val READ_MS = 70_000
    const val NOT_SET = "Cloudflare's browser is not set up. Add the account id and token in Settings, under Tools."
  }
}
