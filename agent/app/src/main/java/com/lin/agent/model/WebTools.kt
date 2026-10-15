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
    return ToolResult.Ok(hitsInWords(query, hits))
  }

  private fun notSetUp(provider: SearchProvider) =
    when (provider) {
      SearchProvider.NONE ->
        "Lin has no search service set up, so it cannot look anything up. Tell the user it is " +
          "in settings under Tools, and that it takes either their own SearXNG address or a " +
          "Brave API key. Then answer from what you know and say you could not check."
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
        "$url answered ${page.status}. Say the page could not be read rather than guessing " +
          "what was on it."
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
