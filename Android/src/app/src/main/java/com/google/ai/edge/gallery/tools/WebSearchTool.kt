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

package com.google.ai.edge.gallery.tools

import android.util.Log
import com.google.ai.edge.gallery.search.PageFetchOutcome
import com.google.ai.edge.gallery.search.ImageDownloadOutcome
import com.google.ai.edge.gallery.search.ImageSearchOutcome
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.search.WebImageHit
import com.google.ai.edge.gallery.search.WebSearchOutcome
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGWebSearchTool"

/**
 * Gives the assistant live web access through the user's own SearXNG instance.
 *
 * Two capabilities are exposed:
 * - [webSearch] returns ranked result snippets, which is usually enough to answer a question.
 * - [openWebPage] downloads one of those results as plain text when the snippets are too thin.
 *
 * Both are read-only and safe to run without a confirmation prompt, so [alwaysAllow] is true. When
 * no instance is configured the tools still exist but return an actionable error, which keeps the
 * model from silently inventing an answer.
 */
class WebSearchTool(
  private val searxngClient: SearxngClient,
  /**
   * Puts a picture into the conversation.
   *
   * Routed through the same slot the JavaScript skill runtime uses, which the chat screen already
   * drains once a turn finishes - so a picture arrives as an ordinary chat message with no new
   * plumbing, and none of the ordering problems of pushing one mid-turn.
   */
  private val showImageInChat: (base64: String) -> Unit = {},
  /** Puts a web page into the conversation, through the same slot. */
  private val showPageInChat: (url: String) -> Unit = {},
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Search the live web through the user's private SearXNG instance. Use this whenever the " +
        "answer depends on current events, prices, schedules, local information, or anything " +
        "that may have changed since training. Returns ranked results with titles, URLs and " +
        "snippets."
  )
  fun webSearch(
    @ToolParam(description = "What to search for, phrased as a normal search query.")
    query: String,
    // Both optional and both rarely wanted, so both are described in as few words as will still
    // produce a valid value. Every character of every tool schema is charged against the same
    // budget as the conversation, and a list of ten category names is not what makes a search work.
    @ToolParam(description = "Optional: news, images, videos, map, music, science or it.")
    categories: String = "",
    @ToolParam(description = "Optional recency: day, week, month or year.") timeRange: String = "",
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "webSearch(query='$query', categories='$categories', timeRange='$timeRange')")
      sendProgress(
        label = "Searching the web for \"$query\"",
        inProgress = true,
        itemTitle = "Web search",
        itemDescription = buildString {
          append("- Query: $query")
          if (categories.isNotBlank()) append("\n- Categories: $categories")
          if (timeRange.isNotBlank()) append("\n- Time range: $timeRange")
        },
      )

      when (
        val outcome =
          searxngClient.search(query = query, categories = categories, timeRange = timeRange)
      ) {
        is WebSearchOutcome.Failure -> {
          sendProgress(label = "Web search failed", inProgress = false)
          mapOf("status" to "failed", "error" to outcome.message)
        }
        is WebSearchOutcome.Success -> {
          sendProgress(
            label = "Searched the web for \"$query\"",
            inProgress = true,
            itemTitle = "Web search returned ${outcome.hits.size} result(s)",
            itemDescription = outcome.hits.joinToString("\n") { "- ${it.title} (${it.url})" },
          )
          buildMap {
            put("status", "succeeded")
            put("query", outcome.query)
            if (outcome.answers.isNotEmpty()) {
              put("direct_answers", outcome.answers)
            }
            if (outcome.infoboxes.isNotEmpty()) {
              put("summaries", outcome.infoboxes)
            }
            put(
              "results",
              outcome.hits.mapIndexed { index, hit ->
                buildMap {
                  put("rank", index + 1)
                  put("title", hit.title)
                  put("url", hit.url)
                  put("snippet", hit.snippet)
                  if (hit.publishedDate.isNotEmpty()) {
                    put("published", hit.publishedDate)
                  }
                }
              },
            )
            put(
              "hint",
              "Answer from these snippets when they are sufficient, and cite the URLs you used. " +
                "Call openWebPage on a result only when you need more detail.",
            )
          }
        }
      }
    }
  }

  @Tool(
    description =
      "Find a picture on the web and show it in the chat. Use for \"show me a photo of...\", " +
        "\"what does X look like\". The picture appears in the conversation on its own."
  )
  fun showImageFromWeb(
    @ToolParam(description = "What the picture should be of, for example 'Mount Fuji at sunrise'.")
    query: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      sendProgress("Looking for a picture of \"$query\"", inProgress = true)
      when (val outcome = searxngClient.searchImages(query)) {
        is ImageSearchOutcome.Failure -> {
          sendProgress("No picture found", inProgress = false)
          failure(outcome.message)
        }
        is ImageSearchOutcome.Success -> {
          // Try the results in order. An image URL from a search index is often stale, hotlink
          // blocked, or a page that merely looks like a picture, and giving up on the first
          // failure would report "no pictures" for a query that has plenty.
          var shown: WebImageHit? = null
          for (candidate in outcome.images) {
            val download = searxngClient.downloadImage(candidate.imageUrl)
            if (download is ImageDownloadOutcome.Success) {
              showImageInChat(download.base64)
              shown = candidate
              break
            }
          }
          if (shown == null) {
            sendProgress("No picture could be loaded", inProgress = false)
            return@runBlocking failure(
              "Pictures were found but none of them could be downloaded. Offer the user the link " +
                "instead: ${outcome.images.firstOrNull()?.sourceUrl.orEmpty()}"
            )
          }
          sendProgress("Showed a picture", inProgress = false)
          mapOf(
            "status" to "succeeded",
            "shown" to shown.title,
            "source" to shown.sourceUrl,
            "hint" to
              "The picture is already in the conversation - do not describe it in detail or " +
                "repeat its URL. Say in one line what it shows and cite the source page.",
          )
        }
      }
    }
  }

  @Tool(
    description =
      "Open a web page inside the chat so the user can read it there. Use when they ask to see " +
        "a link rather than have it summarised."
  )
  fun showWebPageInChat(
    @ToolParam(description = "The full URL to show.") url: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      val trimmed = url.trim()
      if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return@runBlocking failure("\"$url\" is not a web address.")
      }
      showPageInChat(trimmed)
      sendProgress("Opened the page in the chat", inProgress = false)
      mapOf(
        "status" to "succeeded",
        "url" to trimmed,
        "hint" to "The page is in the conversation. Say in one line what it is; do not paste it.",
      )
    }
  }

  @Tool(
    description =
      "Download one web page and return its readable text. Use it after webSearch when a result " +
        "snippet is not detailed enough to answer, or when the user gives you a URL to read."
  )
  fun openWebPage(
    @ToolParam(description = "The full http or https URL of the page to read.") url: String
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "openWebPage(url='$url')")
      sendProgress(
        label = "Reading $url",
        inProgress = true,
        itemTitle = "Open web page",
        itemDescription = "- URL: $url",
      )

      when (val outcome = searxngClient.fetchPageText(url = url)) {
        is PageFetchOutcome.Failure -> {
          sendProgress(label = "Could not read the page", inProgress = false)
          mapOf("status" to "failed", "error" to outcome.message)
        }
        is PageFetchOutcome.Success -> {
          sendProgress(
            label = "Read ${outcome.title.ifEmpty { url }}",
            inProgress = true,
            itemTitle = "Read web page",
            itemDescription = "- ${outcome.title.ifEmpty { url }}\n- ${outcome.text.length} chars",
          )
          buildMap {
            put("status", "succeeded")
            put("url", outcome.url)
            put("title", outcome.title)
            put("text", outcome.text)
            if (outcome.truncated) {
              put("truncated", true)
            }
          }
        }
      }
    }
  }

  private fun failure(message: String): Map<String, Any> =
    mapOf("status" to "failed", "error" to message)

  private suspend fun sendProgress(
    label: String,
    inProgress: Boolean,
    itemTitle: String = "",
    itemDescription: String = "",
  ) {
    executionContext
      ?.actionChannel
      ?.send(
        SkillProgressToolAction(
          label = label,
          inProgress = inProgress,
          addItemTitle = itemTitle,
          addItemDescription = itemDescription,
        )
      )
  }
}
