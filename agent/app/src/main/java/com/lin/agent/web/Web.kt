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

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What came back. [body] holds the error page too, because that is where the reason is. */
data class Fetched(val status: Int, val body: String, val contentType: String) {
  val ok: Boolean
    get() = status in 200..299
}

/**
 * Fetching a page, with the three limits that matter on a phone.
 *
 * **A size cap**, because a page is not a file Lin downloads: it is text about to be put in
 * front of a 2B model, and a ten-megabyte response would be read into memory and then thrown
 * away. **A redirect cap**, because a loop would otherwise be a hang. **A timeout**, because
 * a turn is waiting on this and a slow host must not hold it open.
 *
 * Deliberately no cookie jar, no cache and no custom Accept-Encoding - see the comment in
 * [com.lin.agent.core.web.searchRequest] for why that last one matters.
 */
@Singleton
class Web @Inject constructor() {

  suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Fetched =
    withContext(Dispatchers.IO) {
      var address = url
      var hops = 0
      while (true) {
        val connection =
          (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            // Followed by hand rather than by HttpURLConnection, which refuses to follow one
            // that changes scheme - and http to https is the commonest redirect on the web.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
          }
        try {
          val status = connection.responseCode
          val next = connection.getHeaderField("Location")
          if (status in 300..399 && next != null && hops++ < MOST_HOPS) {
            address = URL(URL(address), next).toString()
            continue
          }
          val stream = if (status in 200..299) connection.inputStream else connection.errorStream
          val text =
            stream?.bufferedReader()?.use { reader ->
              val buffer = CharArray(MOST_CHARS)
              val read = reader.read(buffer, 0, MOST_CHARS)
              if (read <= 0) "" else String(buffer, 0, read)
            }
          return@withContext Fetched(
            status = status,
            body = text.orEmpty(),
            contentType = connection.contentType.orEmpty(),
          )
        } finally {
          connection.disconnect()
        }
      }
      @Suppress("UNREACHABLE_CODE") Fetched(0, "", "")
    }

  private companion object {
    const val CONNECT_MS = 10_000
    const val READ_MS = 20_000
    const val MOST_HOPS = 5

    /**
     * Enough for any article, far short of a file.
     *
     * Read as characters rather than checked afterwards, so a host that sends a hundred
     * megabytes has a hundred megabytes refused rather than buffered.
     */
    const val MOST_CHARS = 400_000

    /**
     * An ordinary browser string.
     *
     * Not an attempt to look like something Lin is not: a great many sites answer an unknown
     * agent with a block page, and a block page is what the user would see as "Lin cannot
     * read anything".
     */
    const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
  }
}
