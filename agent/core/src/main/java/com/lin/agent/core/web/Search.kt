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

/** Where Lin searches. */
enum class SearchProvider {
  /** Nothing configured. The tool says so rather than guessing a provider. */
  NONE,

  /**
   * Wikipedia's own search, and the only one here that needs nothing set up.
   *
   * It exists because of what a fresh install used to be: a model told it could search the
   * web, a tool that answered "no search service is set up", and somebody who has to find an
   * API key before Lin can tell them anything it was not trained on. Encyclopaedic search
   * with no account is worth far more than no search at all.
   *
   * It is not a web search and must never be sold as one, so the results say where they came
   * from. A price, an event, a release date and anything from this week are exactly what
   * Wikipedia is worst at, and a model that forgets which one it is holding would answer them
   * from an encyclopaedia.
   */
  WIKIPEDIA,

  /**
   * The user's own SearXNG, which is the one this project is shaped for: no key, no account,
   * no third party, and they already run a server.
   */
  SEARXNG,

  /** Brave's Web Search API, for somebody who would rather have a key than a server. */
  BRAVE,
}

/**
 * The stored name, defaulting to the one that needs no setting up.
 *
 * Wikipedia rather than NONE, and that is a deliberate change of mind. The first default was
 * "nothing", on the grounds that an app should not pick a third party for somebody - but what
 * it actually produced was an assistant that could not look anything up until its owner had
 * found an API key, and that is a worse imposition than a Wikipedia search nobody asked for.
 * Anybody who wants their own SearXNG or a Brave key is two taps away from it.
 */
fun searchProviderNamed(stored: String?): SearchProvider =
  SearchProvider.entries.firstOrNull { it.name == stored?.trim()?.uppercase() }
    ?: SearchProvider.WIKIPEDIA

/**
 * Where these results came from, said to the model in the result itself.
 *
 * Only where it changes what an honest answer looks like. A model holding six Wikipedia
 * articles and a question about this week's prices should say what it has got, and it cannot
 * do that unless the result says which search it was.
 */
fun sourceNote(provider: SearchProvider): String =
  when (provider) {
    SearchProvider.WIKIPEDIA ->
      "Wikipedia only, not the whole web - say so if the question was about prices, news, " +
        "releases or anything from the last few months"
    else -> ""
  }

/** One result, reduced to what a model can use. */
data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * Where to send the query, and what to send with it.
 *
 * Built here rather than in the app so the address, the escaping and the header are all
 * testable without a network. Returns null when nothing is configured, which the tool reports
 * as "search is not set up" rather than as a failure to reach something.
 */
fun searchRequest(
  provider: SearchProvider,
  query: String,
  baseUrl: String,
  apiKey: String,
  count: Int = 6,
): Pair<String, Map<String, String>>? {
  val asked = query.trim()
  if (asked.isEmpty()) return null
  val escaped = urlEscape(asked)
  return when (provider) {
    SearchProvider.NONE -> null
    // No key, no address, nothing to get wrong - which is the point of it being here.
    SearchProvider.WIKIPEDIA ->
      "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$escaped" +
        "&srlimit=${count.coerceIn(1, 20)}&format=json&utf8=1" to
        mapOf("Accept" to "application/json")
    SearchProvider.SEARXNG -> {
      val base = baseUrl.trim().trimEnd('/')
      if (base.isEmpty()) null
      else "$base/search?q=$escaped&format=json" to mapOf("Accept" to "application/json")
    }
    SearchProvider.BRAVE -> {
      val key = apiKey.trim()
      if (key.isEmpty()) null
      else
        "https://api.search.brave.com/res/v1/web/search?q=$escaped&count=${count.coerceIn(1, 20)}" to
          // Deliberately no Accept-Encoding, although Brave's own examples send one.
          // HttpURLConnection asks for gzip itself and decompresses transparently - but only
          // while the caller has not set the header. Setting it hands back compressed bytes
          // that the JSON parser reads as nothing at all.
          mapOf("Accept" to "application/json", "X-Subscription-Token" to key)
    }
  }
}

/**
 * Results out of whatever the provider sent back.
 *
 * One function rather than two exported ones, because the caller already knows which provider
 * it asked and splitting the dispatch out would only move the `when` somewhere less testable.
 */
fun hitsFrom(provider: SearchProvider, body: String, limit: Int = 6): List<SearchHit> {
  val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
  val hits =
    when (provider) {
      SearchProvider.SEARXNG -> searxngHits(json)
      SearchProvider.BRAVE -> braveHits(json)
      SearchProvider.WIKIPEDIA -> wikipediaHits(json)
      SearchProvider.NONE -> emptyList()
    }
  // Kept when it has an address *or* something to say. A SearXNG direct answer - the point
  // of asking, for a conversion or a definition - has no URL of its own, and a plain
  // `filter { url.isNotEmpty() }` threw it away. Found by the test below it.
  val seen = mutableSetOf<String>()
  return hits
    .filter { it.url.isNotEmpty() || it.snippet.isNotBlank() }
    .filter { it.url.isEmpty() || seen.add(it.url) }
    .take(limit)
}

/**
 * SearXNG's own answer, which may carry a direct answer as well as results.
 *
 * `answers` is worth reading: for a conversion, a definition or a date, SearXNG returns the
 * answer itself and the result list is beside the point. It arrives as the first hit with no
 * URL of its own, so the model sees it before the links.
 */
private fun searxngHits(json: JSONObject): List<SearchHit> {
  val found = mutableListOf<SearchHit>()
  json.optJSONArray("answers")?.let { answers ->
    for (index in 0 until answers.length()) {
      // Older SearXNG sends strings here, newer sends objects. Both appear in the wild.
      val raw = answers.opt(index)
      val text =
        when (raw) {
          is String -> raw
          is JSONObject -> raw.optString("answer").ifEmpty { raw.optString("content") }
          else -> ""
        }
      if (text.isNotBlank()) found.add(SearchHit(title = "Answer", url = "", snippet = text))
    }
  }
  val results = json.optJSONArray("results")
  if (results != null) {
    for (index in 0 until results.length()) {
      val each = results.optJSONObject(index) ?: continue
      found.add(
        SearchHit(
          title = each.optString("title"),
          url = each.optString("url"),
          snippet = each.optString("content"),
        )
      )
    }
  }
  return found.filter { it.url.isNotEmpty() || it.snippet.isNotBlank() }
}

/**
 * Wikipedia's search results.
 *
 * Linked by page id rather than by title. A title has to be percent-encoded, and gets it
 * wrong for exactly the pages whose names contain the interesting characters; `?curid=` is
 * the id the API just handed us and cannot be mangled.
 *
 * The snippets arrive with `<span class="searchmatch">` around every matched word, because
 * the API is written for a web page. Left in, a model reads the markup as part of the text
 * and repeats it.
 */
private fun wikipediaHits(json: JSONObject): List<SearchHit> {
  val results = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
  return (0 until results.length()).mapNotNull { index ->
    val each = results.optJSONObject(index) ?: return@mapNotNull null
    val title = each.optString("title")
    if (title.isEmpty()) return@mapNotNull null
    val id = each.optLong("pageid", 0L)
    SearchHit(
      title = title,
      url = if (id > 0) "https://en.wikipedia.org/?curid=$id" else "",
      snippet = withoutMarkup(each.optString("snippet")),
    )
  }
}

/** Tags out, the handful of entities that matter decoded, and the spacing tidied. */
internal fun withoutMarkup(html: String): String =
  html
    .replace(Regex("<[^>]*>"), "")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
    // Last, so that an escaped ampersand in the source does not turn the text after it into
    // another entity: "&amp;lt;" is the characters "&lt;", not a less-than sign.
    .replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun braveHits(json: JSONObject): List<SearchHit> {
  val results = json.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
  return (0 until results.length()).mapNotNull { index ->
    val each = results.optJSONObject(index) ?: return@mapNotNull null
    SearchHit(
      title = each.optString("title"),
      url = each.optString("url"),
      snippet = each.optString("description"),
    )
  }
}

/**
 * Hits as the model reads them.
 *
 * Numbered, with the address on its own line, because the next thing the model does with a
 * useful-looking result is ask to read it - and a URL buried in a sentence is a URL it
 * copies out wrongly.
 */
fun hitsInWords(query: String, hits: List<SearchHit>, source: String = ""): String {
  if (hits.isEmpty()) return "Nothing came back for \"$query\"."
  return buildString {
    append("Results for \"").append(query).append("\"")
    if (source.isNotBlank()) append(" (").append(source.trim()).append(")")
    append(":")
    hits.forEachIndexed { index, hit ->
      append("\n\n").append(index + 1).append(". ").append(hit.title.ifBlank { hit.url })
      if (hit.url.isNotEmpty()) append("\n").append(hit.url)
      if (hit.snippet.isNotBlank()) append("\n").append(hit.snippet.trim())
    }
  }
}

/**
 * Percent-encoding for a query string.
 *
 * By hand for the same reason the MCP client's is: `URLEncoder` writes a space as `+`, which
 * some engines take literally, and `core` has no business depending on the JDK's form
 * encoding for four lines of work. Public rather than internal because the navigation tool
 * needs the same escaping for a place name in a `geo:` address.
 */
fun urlEscape(text: String): String =
  buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
      val value = byte.toInt() and 0xFF
      val c = value.toChar()
      if (value < 0x80 && (c.isLetterOrDigit() || c in "-_.~")) append(c)
      else append('%').append("%02X".format(value))
    }
  }

/**
 * What to say when the search service answered and there was nothing in it.
 *
 * This exists because of the one failure that matters here: with no results, Lin said *"it's
 * possible there aren't any official Doctor Who Lego sets"* - a confident claim about the
 * world, built out of a search that did not work. An empty result from a web search is almost
 * never news about the world; it is news about the search. So this never reads as "nothing
 * exists", and it says what actually came back so the fault can be found rather than guessed
 * at.
 */
fun nothingCameBack(provider: SearchProvider, body: String): String {
  val what = body.trim()
  val shape =
    when {
      what.isEmpty() -> "an empty reply"
      what.startsWith("{") || what.startsWith("[") -> "data with no results in it"
      else -> "a page rather than data (it starts \"${what.take(40).replace('\n', ' ')}\")"
    }
  return "The search service (${provider.name.lowercase()}) answered with $shape, so this " +
    "lookup did not work. Tell the user the search came back empty and that its settings may " +
    "need checking under Tools - do not tell them the thing they asked about does not exist, " +
    "because nothing here says that."
}

/**
 * One line describing what a trial search actually got, for the settings screen.
 *
 * The screen needs a fact, not a verdict. "Search is not working" sends somebody round in
 * circles; "HTTP 200 - 0 results - 4812 bytes starting <!DOCTYPE html>" tells them, and tells
 * anybody they show it to, exactly which of the four usual faults it is.
 */
fun searchTrialReport(status: Int, hits: Int, body: String): String {
  val head = body.trim().take(40).replace(Regex("\\s+"), " ")
  return buildString {
    append("HTTP ").append(status)
    append(" · ").append(hits).append(if (hits == 1) " result" else " results")
    append(" · ").append(body.length).append(" bytes")
    if (head.isNotEmpty()) append(" starting \"").append(head).append('"')
  }
}
