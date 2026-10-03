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

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

/**
 * Stands in for [WebSearchTool] while no SearXNG instance has been set up.
 *
 * Leaving web search out entirely when it is unconfigured looked like the honest thing to do - a
 * model is never told about a tool that can only fail. What it actually produced was worse than a
 * failure: asked to search the web, the model saw nothing resembling search among its tools and
 * answered from memory instead, with no sign that a search had not happened. The user reads that
 * as the tools not being called, and there is nothing on screen to tell them the one thing they
 * need to know, which is that web search needs an address before it can work.
 *
 * So one tool stands in, under the name the model reaches for, and its only job is to say what is
 * missing and how to fix it. Only one, and not the picture and page-fetching tools alongside it:
 * every schema is charged against the same budget, and one refusal carries the whole message.
 */
class WebSearchSetupTool : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Search the live web. Use whenever the answer depends on current events, prices, schedules " +
        "or anything that may have changed since training."
  )
  fun webSearch(
    @ToolParam(description = "What to search for, phrased as a normal search query.") query: String
  ): Map<String, Any> =
    mapOf(
      "status" to "failed",
      "error" to
        "Web search is not set up yet, so \"$query\" could not be searched for. Tell the user " +
          "that Lin searches through their own SearXNG instance and needs its " +
          "address, which they can add under Web search in the Guide panel. Do not answer the " +
          "question from memory as though a search had been done.",
    )
}
