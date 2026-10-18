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

package com.lin.agent.model

import android.util.Log
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.web.ReadHit
import com.lin.agent.core.web.SearchHit
import com.lin.agent.core.web.SearchProvider
import com.lin.agent.core.web.duckDuckGoForm
import com.lin.agent.core.web.duckDuckGoHits
import com.lin.agent.core.web.goingRoundText
import com.lin.agent.core.web.hitsFrom
import com.lin.agent.core.web.looksLikeABotCheck
import com.lin.agent.core.web.nothingCameBack
import com.lin.agent.core.web.plainerQuery
import com.lin.agent.core.web.readableText
import com.lin.agent.core.web.relevantPassages
import com.lin.agent.core.web.samePagesAgain
import com.lin.agent.core.web.searchBriefing
import com.lin.agent.core.web.searchProviderNamed
import com.lin.agent.core.web.searchCall
import com.lin.agent.core.web.sourceNote
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.web.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looking something up.
 *
 * The largest single thing Lin could not do. An assistant with a calendar, a torch and no way
 * to find out anything it was not trained on is a toy, and the model behind this one was
 * trained small and a while ago.
 *
 * **Not scraped.** The obvious shortcut is to fetch a search engine's HTML and pull the
 * results out of it, and it was tried: the two most-suggested endpoints both answered a plain
 * request with a block page rather than results. Shipping that would mean a feature that
 * works until it quietly does not, for reasons nobody holding the phone could diagnose. So
 * Lin talks to an interface somebody publishes on purpose - the user's own SearXNG, or
 * Brave's documented API - and says plainly when neither is set up.
 */
class SearchWebTool(private val settings: SettingsRepository, private val web: Web) : AgentTool {

  /** What recent searches returned, so a model rewording the same search can be told so. */
  private val recent = ArrayDeque<Pair<Long, Set<String>>>()

  override val name = "search_web"

  override val description =
    "Search the web for something you do not know or that may have changed. Returns a few " +
      "results with their addresses; use read_page on one to read it properly."

  override val parameters =
    listOf(
      ToolParameter(
        name = "query",
        type = ParamType.STRING,
        description = "What to search for, as you would type it into a search box.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR, AgentRole.SUB_AGENT)

  override val guidance =
      "Use search_web when you do not know something or it may have changed since you were " +
      "trained. It reads the top pages for you and gives you what they say: answer from that " +
      "text, not from the titles, and give figures exactly as the pages do. Use read_page " +
      "only for a result it could not read. Always end a web answer with a Sources list of " +
      "the links you used. Never answer a question about current facts, prices or events " +
      "from memory alone."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val query = (arguments["query"] as? String)?.trim().orEmpty()
    if (query.isEmpty()) return ToolResult.Failed("There was nothing to search for.")

    val chosen = searchProviderNamed(runCatching { settings.searchProvider.first() }.getOrNull())
    val first = attempt(chosen, query)
    if (first is Attempt.Found) return ToolResult.Ok(briefed(query, first.hits, sourceNote(chosen)))

    // The question may simply have been over-worded - worth one more try with the same
    // service, but only when it answered and found nothing. A service that is down or
    // refusing will not be fixed by rewording.
    if (first is Attempt.Empty) tryPlainly(query, chosen)?.let { return it }

    // Then the built-in fallbacks, for every kind of failure: not set up, unreachable,
    // refusing, or empty. Each says, in its result, which search failed and why, so neither
    // the model nor the person reading the tool row mistakes a fallback for the real thing.
    for (fallback in FALLBACKS) {
      if (fallback == chosen) continue
      val next = attempt(fallback, query)
      if (next is Attempt.Found) {
        return ToolResult.Ok(briefed(query, next.hits, fallbackNote(chosen, first.why, fallback)))
      }
    }

    // Nothing found is a failure, not an answer. An empty web search is news about the
    // search, not about the world - see nothingCameBack.
    return ToolResult.Failed(
      first.why + " The built-in fallbacks - DuckDuckGo from the phone, then Wikipedia - " +
        "found nothing either."
    )
  }

  /** What one search came to: results, an answer with nothing in it, or no answer at all. */
  private sealed interface Attempt {
    val why: String

    data class Found(val hits: List<SearchHit>) : Attempt {
      override val why = ""
    }

    data class Empty(override val why: String) : Attempt

    data class Broken(override val why: String) : Attempt
  }

  /** One provider, asked once. Every way it can go wrong is named, never just "failed". */
  private suspend fun attempt(provider: SearchProvider, query: String): Attempt {
    val call =
      searchCall(provider, query, baseUrlFor(provider), keyFor(provider))
        ?: return Attempt.Broken(notSetUp(provider))
    val answer =
      runCatching { web.send(call) }
        .onFailure { Log.w(TAG, "${provider.name} search failed", it) }
        .getOrNull()
        ?: return Attempt.Broken("The ${label(provider)} search could not be reached.")
    if (!answer.ok) return Attempt.Broken(whyNot(provider, answer.status))

    val hits = hitsFrom(provider, answer.body)
    if (hits.isNotEmpty()) return Attempt.Found(hits)
    if (provider == SearchProvider.SEARXNG && !answer.body.trimStart().startsWith("{")) {
      // The commonest SearXNG misconfiguration, and it answers 200 with an HTML page rather
      // than an error, so it would otherwise read as "nothing found".
      return Attempt.Broken(
        "That SearXNG replied with a web page rather than data, which means the JSON format " +
          "is not switched on for it. Tell the user to add `json` under `search: formats:` in " +
          "its settings.yml."
      )
    }
    return Attempt.Empty(nothingCameBack(provider, answer.body, call.url))
  }

  /**
   * The same search with the question worded plainly, when the first one found nothing.
   *
   * A model writes "NVIDIA Jetson Orin Nano specs performance 2024 2026" because that reads
   * like a thorough question; a search engine reads it as four extra constraints and can
   * return nothing at all. See [plainerQuery].
   */
  private suspend fun tryPlainly(query: String, provider: SearchProvider): ToolResult? {
    val plainer = plainerQuery(query) ?: return null
    val found = attempt(provider, plainer) as? Attempt.Found ?: return null
    return ToolResult.Ok(
      briefed(
        plainer,
        found.hits,
        "\"$query\" found nothing, so this is the same question in plainer words" +
          sourceNote(provider).let { if (it.isEmpty()) "" else "; $it" },
      )
    )
  }

  private suspend fun baseUrlFor(provider: SearchProvider): String =
    when (provider) {
      SearchProvider.SEARXNG -> runCatching { settings.searchUrl.first() }.getOrDefault("")
      SearchProvider.FIRECRAWL -> runCatching { settings.firecrawlUrl.first() }.getOrDefault("")
      else -> ""
    }

  /** This provider's key and no other's - see the note on the keys in the settings. */
  private suspend fun keyFor(provider: SearchProvider): String =
    when (provider) {
      SearchProvider.BRAVE -> runCatching { settings.searchKey.first() }.getOrDefault("")
      SearchProvider.FIRECRAWL -> runCatching { settings.firecrawlKey.first() }.getOrDefault("")
      SearchProvider.TAVILY -> runCatching { settings.tavilyKey.first() }.getOrDefault("")
      else -> ""
    }

  /**
   * What the model is told when a fallback answered: which search failed, why, and what this
   * is instead. Asked to mention the failure in one line - enough for the person to know their
   * chosen search is not working, without the answer becoming about the failure.
   */
  private fun fallbackNote(chosen: SearchProvider, why: String, used: SearchProvider): String =
    "the ${label(chosen)} search did not work, so these are from ${label(used)}" +
      (if (used == SearchProvider.WIKIPEDIA) " - say it is not a web search" else " - a real web search") +
      ". Mention the failure in one line. What went wrong: $why"

  private fun label(provider: SearchProvider): String =
    when (provider) {
      SearchProvider.NONE -> "no"
      SearchProvider.WIKIPEDIA -> "Wikipedia"
      SearchProvider.DUCKDUCKGO -> "DuckDuckGo (searched from the phone)"
      SearchProvider.SEARXNG -> "SearXNG"
      SearchProvider.BRAVE -> "Brave"
      SearchProvider.FIRECRAWL -> "Firecrawl"
      SearchProvider.TAVILY -> "Tavily"
    }

  /**
   * The results, with the top pages actually read.
   *
   * The complaint that led here: asked for the Orin Nano's specifications, Lin found NVIDIA's
   * own product page and answered from its title. The search engine's snippet is a sentence
   * chosen to make somebody click, not the answer, and a model that only sees titles and
   * snippets can only ever paraphrase a results page. So the top few are fetched, reduced to
   * their text, and narrowed to the parts that answer the question - see [relevantPassages].
   *
   * In parallel and with a deadline. Three pages one after another is most of a minute on a
   * slow site, and one page that never answers must not hold the whole answer hostage: what
   * has arrived by the deadline is used, and a page that missed it falls back to its snippet,
   * marked as such so the model knows the difference.
   */
  private suspend fun briefed(query: String, hits: List<SearchHit>, note: String): String {
    // Going round in a circle is caught before any page is fetched: the model has already
    // been given these pages, and fetching them again only makes the circle slower.
    val urls = hits.mapNotNull { it.url.takeIf(String::isNotEmpty) }
    val now = System.currentTimeMillis()
    val earlier =
      synchronized(recent) {
        recent.removeAll { now - it.first > RECENT_MS }
        recent.map { it.second }
      }
    if (samePagesAgain(urls, earlier)) return goingRoundText(query, hits.map { it.title })
    synchronized(recent) { recent.add(now to urls.toSet()) }

    // One more than will be used: when a page turns out to be a bot check, the next result
    // takes its place rather than the answer having one source fewer.
    // Pages the service already sent - Firecrawl and Tavily do - are used as they are. Only
    // the rest are fetched, and only as many as are still needed.
    val sent =
      hits
        .filter { it.page.isNotBlank() && !looksLikeABotCheck(it.page) }
        .take(PAGES_READ)
        .associate { it.url to relevantPassages(it.page, query, PASSAGE_BUDGET) }
    val toRead =
      hits
        .filter { it.url.startsWith("http") && it.url !in sent }
        .take((PAGES_READ - sent.size).coerceAtLeast(0) + 1)
        .takeIf { sent.size < PAGES_READ }
        .orEmpty()
    val reader = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val pending = toRead.map { hit -> hit to reader.async { runCatching { web.get(hit.url) }.getOrNull() } }
    val deadline = System.currentTimeMillis() + READ_DEADLINE_MS
    val texts = sent.toMutableMap()
    try {
      for ((hit, job) in pending) {
        val left = deadline - System.currentTimeMillis()
        val page = if (left <= 0) null else withTimeoutOrNull(left) { job.await() }
        if (page == null || !page.ok) continue
        if (page.contentType.isNotEmpty() && READABLE.none { page.contentType.startsWith(it) }) continue
        if (texts.size >= PAGES_READ) break
        val text = readableText(page.body)
        // A challenge page is not the page. Left as unread, so it falls back to its snippet,
        // marked as such - a small model will otherwise summarise a CAPTCHA.
        if (looksLikeABotCheck(text)) continue
        val passages = relevantPassages(text, query, PASSAGE_BUDGET)
        if (passages.isNotBlank()) texts[hit.url] = passages
      }
    } finally {
      // Stragglers are abandoned rather than waited for: their answer is no longer wanted.
      reader.cancel()
    }
    return searchBriefing(query, hits.map { ReadHit(it, texts[it.url].orEmpty()) }, note)
  }

  private fun notSetUp(provider: SearchProvider) =
    when (provider) {
      SearchProvider.NONE ->
        "Lin has no search service set up, so it cannot look anything up. Tell the user it is " +
          "in settings under Tools. Then answer from what you know and say you could not check."
      // Unreachable: neither needs anything filled in, so their requests are never null. Here
      // because the compiler is right to insist, and a silent `else` would hide the day a new
      // provider arrives without its own sentence.
      SearchProvider.WIKIPEDIA,
      SearchProvider.DUCKDUCKGO ->
        "The ${label(provider)} search could not be built, which should not happen. Say the " +
          "lookup failed rather than guessing an answer."
      SearchProvider.FIRECRAWL ->
        "Firecrawl is chosen but has no API key, and no self-hosted address is set either. Say " +
          "one of them needs filling in under Tools."
      SearchProvider.TAVILY ->
        "Tavily is chosen but no API key is set. Say it needs filling in under Tools."
      SearchProvider.SEARXNG ->
        "A SearXNG is chosen but its address is empty. Say it needs filling in under Tools."
      SearchProvider.BRAVE ->
        "Brave search is chosen but no API key is set. Say it needs filling in under Tools."
    }

  private fun whyNot(provider: SearchProvider, status: Int) =
    when {
      status == 403 && provider == SearchProvider.SEARXNG ->
        "That SearXNG refused the request, which almost always means the JSON format is not " +
          "enabled on it. Tell the user to add `json` under `search: formats:` in settings.yml."
      status == 401 || status == 403 ->
        "The search service rejected the key. Say it needs checking in settings."
      status == 429 ->
        "The search service is rate limiting. Say so, and that waiting a moment should fix it."
      else -> "The search service answered $status. Say the lookup failed rather than guessing."
    }

  private companion object {
    const val TAG = "LinSearchWeb"

    /**
     * What is tried, in order, when the chosen search does not work: a real web search from
     * the phone, then the encyclopaedia. Neither needs anything set up, which is what lets
     * them be the floor under every other choice.
     */
    val FALLBACKS = listOf(SearchProvider.DUCKDUCKGO, SearchProvider.WIKIPEDIA)

    /** How long a search's pages count as already given: about one question's worth. */
    const val RECENT_MS = 3 * 60 * 1000L

    /** How many results are read in full. Three covers the official page and two others. */
    const val PAGES_READ = 3

    /**
     * Characters kept from each page. Three of these is under two thousand tokens, which
     * leaves a 4k context room for the conversation as well.
     */
    const val PASSAGE_BUDGET = 2_500

    /** How long the pages get, all together, before the answer goes ahead without them. */
    const val READ_DEADLINE_MS = 9_000L

    val READABLE = listOf("text/", "application/xhtml", "application/xml")
  }
}

/**
 * Reading one page properly.
 *
 * The other half of searching: a result list gives a sentence each, and the answer is usually
 * in the page. What arrives at the model is the text a person would read - see
 * [readableText], which is where the markup, the navigation and the scripts are taken out,
 * and which is tested, because a page that arrived as raw HTML would fill the whole context
 * window with angle brackets.
 */
class ReadPageTool(private val web: Web) : AgentTool {

  override val name = "read_page"

  override val description =
    "Read a web page and get the words on it. Use it on an address from search_web, or one " +
      "the user gave you."

  override val parameters =
    listOf(
      ToolParameter(
        name = "url",
        type = ParamType.STRING,
        description = "The full address, starting with http:// or https://",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR, AgentRole.SUB_AGENT)

  override val guidance =
    "read_page only reads an address that came from a search result or from the user. Never " +
      "assemble one that looks right - a guessed address answers 404, which costs a turn and " +
      "tells you nothing. If you have no address, search for one first."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["url"] as? String)?.trim().orEmpty()
    if (asked.isEmpty()) return ToolResult.Failed("No address was given.")
    // A bare host is what a model writes when it is copying from a result list, and it is
    // unambiguous enough to fix rather than refuse.
    val url = if (asked.startsWith("http://") || asked.startsWith("https://")) asked else "https://$asked"
    if (!url.contains('.')) {
      return ToolResult.Failed("\"$asked\" is not a web address. Ask the user for the link.")
    }

    val page =
      runCatching { web.get(url) }
        .onFailure { Log.w(TAG, "Could not fetch $url", it) }
        .getOrNull() ?: return ToolResult.Failed("$url could not be reached.")

    if (!page.ok) {
      return ToolResult.Failed(
        "$url answered ${page.status}." +
          // Almost always a model that assembled a plausible-looking address rather than
          // using one from a result list. Worth saying, because the fix is to search.
          (if (page.status == 404) " That address does not exist - if you assembled it " +
            "rather than taking it from a search result, search for the page instead." else "") +
          " Say the page could not be read rather than guessing what was on it."
      )
    }

    // A PDF or an image arrives as bytes that make no sense as text, and the model would
    // describe the mojibake as though it were the page.
    if (page.contentType.isNotEmpty() && TEXTUAL.none { page.contentType.startsWith(it) }) {
      return ToolResult.Failed(
        "$url is ${page.contentType.substringBefore(';')}, which Lin cannot read as text. Say so."
      )
    }

    val text = readableText(page.body)
    if (text.isBlank()) {
      return ToolResult.Failed(
        "$url has no readable text - it may be a page that builds itself with JavaScript, " +
          "which Lin does not run. Say so."
      )
    }
    return ToolResult.Ok("From $url:\n\n$text")
  }

  private companion object {
    const val TAG = "LinReadPage"
    val TEXTUAL = listOf("text/", "application/xhtml", "application/xml", "application/json")
  }
}
