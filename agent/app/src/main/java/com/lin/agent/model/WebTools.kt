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
import com.lin.agent.core.web.SearchProvider
import com.lin.agent.core.web.hitsFrom
import com.lin.agent.core.web.hitsInWords
import com.lin.agent.core.web.nothingCameBack
import com.lin.agent.core.web.sourceNote
import com.lin.agent.core.web.readableText
import com.lin.agent.core.web.searchProviderNamed
import com.lin.agent.core.web.searchRequest
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.web.Web
import kotlinx.coroutines.flow.first

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
      "trained, then read_page on a result worth reading. Never answer a question about " +
      "current facts, prices or events from memory alone."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val query = (arguments["query"] as? String)?.trim().orEmpty()
    if (query.isEmpty()) return ToolResult.Failed("There was nothing to search for.")

    val provider = searchProviderNamed(runCatching { settings.searchProvider.first() }.getOrNull())
    val request =
      searchRequest(
        provider = provider,
        query = query,
        baseUrl = runCatching { settings.searchUrl.first() }.getOrDefault(""),
        apiKey = runCatching { settings.searchKey.first() }.getOrDefault(""),
      )
        ?: return ToolResult.Failed(notSetUp(provider))

    val (url, headers) = request
    val answer =
      runCatching { web.get(url, headers) }
        .onFailure { Log.w(TAG, "Search failed", it) }
        .getOrNull()
        ?: return ToolResult.Failed(
          "The search service could not be reached. Say so and answer from what you know, " +
            "making clear that you could not look it up."
        )

    if (!answer.ok) return ToolResult.Failed(whyNot(provider, answer.status))

    val hits = hitsFrom(provider, answer.body)
    if (hits.isEmpty() && provider == SearchProvider.SEARXNG && !answer.body.trimStart().startsWith("{")) {
      // The commonest SearXNG misconfiguration, and it answers 200 with an HTML page rather
      // than an error, so it would otherwise read as "nothing found".
      return ToolResult.Failed(
        "That SearXNG replied with a web page rather than data, which means the JSON format " +
          "is not switched on for it. Tell the user to add `json` under `search: formats:` in " +
          "its settings.yml."
      )
    }
    // Nothing found is a failure, not an answer. This used to return Ok with "Nothing came
    // back", and what the user saw was Lin saying "it's possible there aren't any official
    // Doctor Who Lego sets" - a confident claim about the world assembled out of a search
    // that did not work. An empty web search is news about the search, not about the world.
    if (hits.isEmpty()) return insteadOfNothing(query, provider, answer.body)
    return ToolResult.Ok(hitsInWords(query, hits, sourceNote(provider)))
  }

  /**
   * One more try at Wikipedia, when the chosen search came back with nothing.
   *
   * Because of what nothing actually means here. The report that led to this was a SearXNG
   * whose engines had started refusing it - no change at either end, the instance simply
   * blocked by Google and the rest - and the honest failure message, while correct, left the
   * user with an assistant that could not look anything up until they had fixed a server.
   * Wikipedia needs nothing and was already in the app.
   *
   * Labelled every time. The result says which search returned nothing, and that what came
   * back instead is an encyclopaedia, so the model cannot pass one off as the other. If
   * Wikipedia is empty too, the original diagnosis is what the model gets.
   */
  private suspend fun insteadOfNothing(
    query: String,
    provider: SearchProvider,
    body: String,
  ): ToolResult {
    val why = nothingCameBack(provider, body)
    if (provider == SearchProvider.WIKIPEDIA) return ToolResult.Failed(why)

    val (url, headers) =
      searchRequest(SearchProvider.WIKIPEDIA, query, baseUrl = "", apiKey = "")
        ?: return ToolResult.Failed(why)
    val answer = runCatching { web.get(url, headers) }.getOrNull()
    val hits =
      if (answer?.ok == true) hitsFrom(SearchProvider.WIKIPEDIA, answer.body) else emptyList()
    // Said out loud, because otherwise the model is told only about the SearXNG and reports
    // one failure where there were two - and "Wikipedia had nothing either" is the part that
    // tells the user the question itself may need rewording.
    if (hits.isEmpty()) {
      return ToolResult.Failed("$why Wikipedia was tried as well and had nothing for this query.")
    }

    return ToolResult.Ok(
      hitsInWords(
        query,
        hits,
        "the ${provider.name.lowercase()} search returned nothing, so these are from " +
          "Wikipedia instead - say that, and say it is not a web search",
      )
    )
  }

  private fun notSetUp(provider: SearchProvider) =
    when (provider) {
      SearchProvider.NONE ->
        "Lin has no search service set up, so it cannot look anything up. Tell the user it is " +
          "in settings under Tools, and that it takes either their own SearXNG address or a " +
          "Brave API key. Then answer from what you know and say you could not check."
      // Unreachable: Wikipedia needs nothing filled in, so its request is never null. Here
      // because the compiler is right to insist, and a silent `else` would hide the day a
      // fourth provider arrives without its own sentence.
      SearchProvider.WIKIPEDIA ->
        "The Wikipedia search could not be built, which should not happen. Say the lookup " +
          "failed rather than guessing an answer."
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
