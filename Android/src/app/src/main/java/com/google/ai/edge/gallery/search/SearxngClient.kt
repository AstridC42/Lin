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

package com.google.ai.edge.gallery.search

import android.util.Log
import com.google.ai.edge.gallery.proto.WebSearchSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGSearxngClient"

/** A single hit returned by a SearXNG instance. */
data class WebSearchHit(
  val title: String,
  val url: String,
  val snippet: String,
  val engine: String = "",
  val publishedDate: String = "",
)

/** The outcome of one query against a SearXNG instance. */
sealed interface WebSearchOutcome {
  data class Success(
    val query: String,
    val hits: List<WebSearchHit>,
    /** Direct answers ("2 + 2 = 4", unit conversions, ...) computed by SearXNG itself. */
    val answers: List<String> = emptyList(),
    /** Short encyclopedia-style summaries SearXNG collected for the query. */
    val infoboxes: List<String> = emptyList(),
  ) : WebSearchOutcome

  data class Failure(val message: String) : WebSearchOutcome
}

/** A single picture returned by an image search. */
data class WebImageHit(
  val title: String,
  /** The picture itself. */
  val imageUrl: String,
  /** The page it appears on, so a citation points somewhere a person can read. */
  val sourceUrl: String,
)

/** The outcome of one image query. */
sealed interface ImageSearchOutcome {
  data class Success(val query: String, val images: List<WebImageHit>) : ImageSearchOutcome

  data class Failure(val message: String) : ImageSearchOutcome
}

/** The outcome of downloading one picture. */
sealed interface ImageDownloadOutcome {
  data class Success(val url: String, val base64: String) : ImageDownloadOutcome

  data class Failure(val message: String) : ImageDownloadOutcome
}

/** The outcome of downloading a single web page as plain text. */
sealed interface PageFetchOutcome {
  data class Success(val url: String, val title: String, val text: String, val truncated: Boolean) :
    PageFetchOutcome

  data class Failure(val message: String) : PageFetchOutcome
}

/**
 * Minimal client for the JSON API of a self-hosted
 * [SearXNG](https://docs.searxng.org/) metasearch instance.
 *
 * The instance must have `json` listed under `search.formats` in its `settings.yml`; most public
 * instances only serve HTML, which is why Star Storyteller expects a private instance. Every call
 * is issued with `HttpURLConnection` on [Dispatchers.IO] so no extra HTTP dependency is pulled in.
 */
class SearxngClient(private val readSettings: () -> WebSearchSettings) {

  /** Whether a usable instance has been configured and enabled by the user. */
  fun isConfigured(): Boolean {
    val settings = readSettings()
    return settings.enabled && normalizeBaseUrl(settings.instanceUrl) != null
  }

  /** The configured instance URL, normalized, or null when web search is unavailable. */
  fun instanceUrlOrNull(): String? {
    val settings = readSettings()
    if (!settings.enabled) return null
    return normalizeBaseUrl(settings.instanceUrl)
  }

  /**
   * Runs [query] against the configured instance.
   *
   * @param categories comma separated SearXNG categories; falls back to the configured default.
   * @param timeRange one of `day`, `week`, `month`, `year`; empty for no restriction.
   * @param maxResults upper bound on returned hits; falls back to the configured default.
   */
  suspend fun search(
    query: String,
    categories: String = "",
    timeRange: String = "",
    maxResults: Int = 0,
  ): WebSearchOutcome =
    withContext(Dispatchers.IO) {
      val settings = readSettings()
      val baseUrl =
        instanceUrlOrNull()
          ?: return@withContext WebSearchOutcome.Failure(
            "Web search is not set up. Ask the user to add their SearXNG instance URL in " +
              "Settings > Web search."
          )
      if (query.isBlank()) {
        return@withContext WebSearchOutcome.Failure("The search query is empty.")
      }

      val limit = (maxResults.takeIf { it > 0 } ?: settings.maxResults.takeIf { it > 0 } ?: 5)
      val effectiveCategories =
        categories.ifBlank { settings.categories.ifBlank { DEFAULT_CATEGORIES } }
      val language = settings.language.ifBlank { "auto" }
      val safeSearch = settings.safeSearch.coerceIn(0, 2)

      val params =
        buildList {
            add("q" to query)
            add("format" to "json")
            add("categories" to effectiveCategories)
            add("language" to language)
            add("safesearch" to safeSearch.toString())
            add("pageno" to "1")
            if (timeRange.isNotBlank()) {
              add("time_range" to timeRange)
            }
          }
          .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

      val requestUrl = "$baseUrl/search?$params"
      Log.d(TAG, "Searching: $requestUrl")

      val body =
        try {
          httpGet(requestUrl, settings, accept = "application/json")
        } catch (e: SearxngHttpException) {
          return@withContext WebSearchOutcome.Failure(describeHttpFailure(e, baseUrl))
        } catch (e: IOException) {
          return@withContext WebSearchOutcome.Failure(
            describeNetworkFailure(e, baseUrl)
          )
        }

      return@withContext try {
        parseSearchResponse(query = query, body = body, limit = limit)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse SearXNG response", e)
        WebSearchOutcome.Failure(
          "The instance at $baseUrl did not return JSON. Add `- json` to `search.formats` in " +
            "its settings.yml and restart it."
        )
      }
    }

  /** Downloads [url] and reduces it to readable plain text, capped at [maxChars] characters. */
  /**
   * Searches for pictures.
   *
   * SearXNG's image category returns a separate `img_src` alongside the page URL, and the two are
   * not interchangeable: `img_src` is what can be displayed, `url` is the page it came from and is
   * what a citation should point at. Results without an `img_src` are dropped rather than shown as
   * a link that looks like a picture and is not.
   */
  suspend fun searchImages(query: String, limit: Int = DEFAULT_IMAGE_LIMIT): ImageSearchOutcome =
    withContext(Dispatchers.IO) {
      val settings = readSettings()
      val baseUrl =
        instanceUrlOrNull()
          ?: return@withContext ImageSearchOutcome.Failure(
            "No SearXNG instance is set up, so pictures cannot be looked up."
          )
      if (query.isBlank()) {
        return@withContext ImageSearchOutcome.Failure("The search query is empty.")
      }
      val params =
        buildList {
            add("q" to query)
            add("format" to "json")
            add("categories" to "images")
            add("language" to settings.language.ifBlank { "auto" })
            add("safesearch" to settings.safeSearch.coerceIn(0, 2).toString())
            add("pageno" to "1")
          }
          .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

      val body =
        try {
          httpGet("$baseUrl/search?$params", settings, accept = "application/json")
        } catch (e: SearxngHttpException) {
          return@withContext ImageSearchOutcome.Failure(describeHttpFailure(e, baseUrl))
        } catch (e: IOException) {
          return@withContext ImageSearchOutcome.Failure(
            describeNetworkFailure(e, baseUrl)
          )
        }

      try {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: JSONArray()
        val images = mutableListOf<WebImageHit>()
        for (i in 0 until results.length()) {
          if (images.size >= limit) break
          val item = results.optJSONObject(i) ?: continue
          val imageUrl = item.optString("img_src").trim()
          if (imageUrl.isEmpty()) continue
          val pageUrl = item.optString("url").trim()
          images.add(
            WebImageHit(
              title = item.optString("title").trim().ifEmpty { query },
              imageUrl = absolute(imageUrl, baseUrl),
              sourceUrl = pageUrl,
            )
          )
        }
        if (images.isEmpty()) {
          return@withContext ImageSearchOutcome.Failure("No pictures found for \"$query\".")
        }
        ImageSearchOutcome.Success(query = query, images = images)
      } catch (e: Exception) {
        Log.e(TAG, "Could not read the image results", e)
        ImageSearchOutcome.Failure(
          "The instance at $baseUrl did not return usable image results."
        )
      }
    }

  /**
   * Downloads one picture, as base64, so the chat can show it.
   *
   * Bounded on purpose. The bytes are held in memory, base64 grows them by a third, and the result
   * is put into a chat message - so a full-resolution photograph would cost far more than it is
   * worth on a phone that is also holding a language model in RAM.
   */
  suspend fun downloadImage(url: String, maxBytes: Int = MAX_IMAGE_BYTES): ImageDownloadOutcome =
    withContext(Dispatchers.IO) {
      if (url.isBlank()) return@withContext ImageDownloadOutcome.Failure("No picture URL.")
      val settings = readSettings()
      val timeout = (settings.timeoutSeconds.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS) * 1000
      var connection: HttpURLConnection? = null
      try {
        connection =
          (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "image/*")
          }
        val contentType = connection.contentType.orEmpty()
        if (contentType.isNotEmpty() && !contentType.startsWith("image/")) {
          return@withContext ImageDownloadOutcome.Failure(
            "That address is not a picture (it returned $contentType)."
          )
        }
        // Content-Length is a hint and is often absent or wrong, so the read below is bounded too.
        val declared = connection.contentLength
        if (declared > maxBytes) {
          return@withContext ImageDownloadOutcome.Failure("That picture is too large to show.")
        }
        val bytes =
          connection.inputStream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
              val read = input.read(chunk)
              if (read <= 0) break
              buffer.write(chunk, 0, read)
              if (buffer.size() > maxBytes) {
                return@withContext ImageDownloadOutcome.Failure(
                  "That picture is too large to show."
                )
              }
            }
            buffer.toByteArray()
          }
        if (bytes.isEmpty()) {
          return@withContext ImageDownloadOutcome.Failure("That picture came back empty.")
        }
        ImageDownloadOutcome.Success(
          url = url,
          base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Could not download $url", e)
        ImageDownloadOutcome.Failure("That picture could not be downloaded.")
      } finally {
        connection?.disconnect()
      }
    }

  /** SearXNG returns some image URLs relative to itself, typically its own proxy. */
  private fun absolute(candidate: String, baseUrl: String): String =
    when {
      candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
      candidate.startsWith("//") -> "https:$candidate"
      candidate.startsWith("/") -> "$baseUrl$candidate"
      else -> candidate
    }

  suspend fun fetchPageText(url: String, maxChars: Int = DEFAULT_PAGE_CHAR_LIMIT): PageFetchOutcome =
    withContext(Dispatchers.IO) {
      val settings = readSettings()
      if (!settings.allowPageFetch) {
        return@withContext PageFetchOutcome.Failure(
          "Reading full pages is turned off in Settings > Web search."
        )
      }
      val parsed =
        try {
          URL(url)
        } catch (e: Exception) {
          return@withContext PageFetchOutcome.Failure("'$url' is not a valid URL.")
        }
      if (parsed.protocol != "http" && parsed.protocol != "https") {
        return@withContext PageFetchOutcome.Failure("Only http and https URLs can be opened.")
      }

      val html =
        try {
          httpGet(url, settings, accept = "text/html,application/xhtml+xml")
        } catch (e: SearxngHttpException) {
          return@withContext PageFetchOutcome.Failure(
            "The page returned HTTP ${e.statusCode} (${e.statusMessage})."
          )
        } catch (e: IOException) {
          return@withContext PageFetchOutcome.Failure(
            describeNetworkFailure(e, url)
          )
        }

      val title = extractTitle(html)
      val text = htmlToPlainText(html)
      val truncated = text.length > maxChars
      PageFetchOutcome.Success(
        url = url,
        title = title,
        text = if (truncated) text.take(maxChars) else text,
        truncated = truncated,
      )
    }

  private fun parseSearchResponse(query: String, body: String, limit: Int): WebSearchOutcome {
    val root = JSONObject(body)
    val resultsJson = root.optJSONArray("results") ?: JSONArray()
    val hits = mutableListOf<WebSearchHit>()
    for (i in 0 until resultsJson.length()) {
      if (hits.size >= limit) break
      val item = resultsJson.optJSONObject(i) ?: continue
      val hitUrl = item.optString("url").trim()
      if (hitUrl.isEmpty()) continue
      hits.add(
        WebSearchHit(
          title = item.optString("title").trim().ifEmpty { hitUrl },
          url = hitUrl,
          snippet = item.optString("content").trim(),
          engine = item.optString("engine").trim(),
          publishedDate = item.optString("publishedDate").trim(),
        )
      )
    }

    val answers = mutableListOf<String>()
    root.optJSONArray("answers")?.let { array ->
      for (i in 0 until array.length()) {
        // SearXNG < 2024 returns plain strings here, newer versions return objects.
        when (val entry = array.opt(i)) {
          is String -> entry.trim().takeIf { it.isNotEmpty() }?.let(answers::add)
          is JSONObject ->
            entry.optString("answer").trim().takeIf { it.isNotEmpty() }?.let(answers::add)
        }
      }
    }

    val infoboxes = mutableListOf<String>()
    root.optJSONArray("infoboxes")?.let { array ->
      for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val content = entry.optString("content").trim()
        if (content.isNotEmpty()) {
          val label = entry.optString("infobox").trim()
          infoboxes.add(if (label.isEmpty()) content else "$label: $content")
        }
      }
    }

    if (hits.isEmpty() && answers.isEmpty() && infoboxes.isEmpty()) {
      val unresponsive = root.optJSONArray("unresponsive_engines")?.length() ?: 0
      return WebSearchOutcome.Failure(
        if (unresponsive > 0) {
          "The instance returned no results; $unresponsive of its engines did not respond."
        } else {
          "No results for \"$query\"."
        }
      )
    }
    return WebSearchOutcome.Success(
      query = query,
      hits = hits,
      answers = answers,
      infoboxes = infoboxes,
    )
  }

  private fun httpGet(url: String, settings: WebSearchSettings, accept: String): String {
    val timeoutMs = (settings.timeoutSeconds.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS) * 1000
    var connection: HttpURLConnection? = null
    try {
      connection = (URL(url).openConnection() as HttpURLConnection)
      connection.requestMethod = "GET"
      connection.connectTimeout = timeoutMs
      connection.readTimeout = timeoutMs
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Accept", accept)
      connection.setRequestProperty("Accept-Encoding", "gzip")
      connection.setRequestProperty("User-Agent", USER_AGENT)
      if (settings.authHeader.isNotBlank()) {
        connection.setRequestProperty("Authorization", settings.authHeader)
      }

      val status = connection.responseCode
      if (status !in 200..299) {
        throw SearxngHttpException(status, connection.responseMessage ?: "")
      }
      val rawStream = connection.inputStream
      val stream =
        if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
          GZIPInputStream(rawStream)
        } else {
          rawStream
        }
      return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * What to say when the request never reached the instance.
   *
   * Worth more than the exception's own text in one case. Android refuses cleartext by default,
   * so a plain `http://` address on the home network - which is what a self-hosted instance
   * almost always is - fails before anything leaves the phone, with a message that reads like the
   * server is down. Naming it is the difference between a user checking their router and a user
   * knowing the address is right and the app will not use it.
   */
  private fun describeNetworkFailure(e: IOException, target: String): String {
    val detail = e.message.orEmpty()
    if (detail.contains("Cleartext", ignoreCase = true)) {
      return "This build will not make plain http:// requests, so $target was never contacted. " +
        "Either serve the instance over https://, or ask for cleartext to the home network to " +
        "be turned back on in the app."
    }
    return "Could not reach $target (${detail.ifEmpty { "network error" }})."
  }

  private fun describeHttpFailure(e: SearxngHttpException, baseUrl: String): String =
    when (e.statusCode) {
      403 ->
        "The instance at $baseUrl refused the request (HTTP 403). SearXNG blocks the JSON API " +
          "unless `json` is listed under `search.formats` in settings.yml."
      401 ->
        "The instance at $baseUrl requires authentication (HTTP 401). Add an Authorization " +
          "header in Settings > Web search."
      404 -> "No SearXNG endpoint at $baseUrl/search (HTTP 404). Check the instance URL."
      429 -> "The instance at $baseUrl is rate limiting this device (HTTP 429). Try again shortly."
      else -> "The instance at $baseUrl returned HTTP ${e.statusCode} (${e.statusMessage})."
    }

  private class SearxngHttpException(val statusCode: Int, val statusMessage: String) :
    IOException("HTTP $statusCode $statusMessage")

  companion object {
    const val DEFAULT_CATEGORIES = "general"
    const val DEFAULT_TIMEOUT_SECONDS = 20
    const val DEFAULT_MAX_RESULTS = 5
    const val DEFAULT_PAGE_CHAR_LIMIT = 6000

    /** How many pictures to offer. More than a handful is noise on a phone screen. */
    const val DEFAULT_IMAGE_LIMIT = 6

    /** Largest picture worth base64-ing into a chat message. */
    const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36 StarStoryteller"

    /**
     * Trims trailing slashes and adds a scheme when the user typed a bare host.
     *
     * @return the normalized base URL, or null when [raw] cannot be a URL at all.
     */
    fun normalizeBaseUrl(raw: String): String? {
      val trimmed = raw.trim().trimEnd('/')
      if (trimmed.isEmpty()) return null
      val withScheme =
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
      return try {
        val parsed = URL(withScheme)
        if (parsed.host.isNullOrEmpty()) null else withScheme
      } catch (e: Exception) {
        null
      }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val DROPPED_ELEMENTS_REGEX =
      Regex(
        "<(script|style|noscript|svg|head|nav|footer|form)[^>]*>.*?</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
      )
    private val BLOCK_END_REGEX =
      Regex("</(p|div|li|tr|h[1-6]|section|article|br)\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL)
    private val BLANK_LINES_REGEX = Regex("\\n{3,}")
    private val INLINE_SPACES_REGEX = Regex("[ \\t\\x0B\\f\\r]+")

    private fun extractTitle(html: String): String =
      TITLE_REGEX.find(html)?.groupValues?.get(1)?.let { unescapeHtml(it).trim() } ?: ""

    /**
     * Strips markup from [html] and keeps the paragraph structure, which is enough context for a
     * small on-device model to summarize a page.
     */
    fun htmlToPlainText(html: String): String {
      val withoutNoise = DROPPED_ELEMENTS_REGEX.replace(html, " ")
      val withBreaks = BLOCK_END_REGEX.replace(withoutNoise, "\n")
      val withoutTags = TAG_REGEX.replace(withBreaks, " ")
      return unescapeHtml(withoutTags)
        .lineSequence()
        .map { INLINE_SPACES_REGEX.replace(it, " ").trim() }
        .joinToString("\n")
        .let { BLANK_LINES_REGEX.replace(it, "\n\n") }
        .trim()
    }

    private fun unescapeHtml(value: String): String =
      value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
  }
}
