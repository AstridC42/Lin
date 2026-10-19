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

import java.net.URI

/**
 * The picture a page offers for itself: the one a link preview shows.
 *
 * Every assistant that searches well shows pictures beside the answer, and an image-search API
 * is one more key to ask for. But almost every page already names its own preview image in
 * `og:image` or `twitter:image`, chosen by the people who made the page to represent it - so
 * reading the pages Lin is already reading gives pictures with every search service, for free.
 */
fun pageImage(html: String, pageUrl: String): String? {
  val head = html.take(HEAD_BYTES)
  for (meta in META.findAll(head)) {
    val tag = meta.value
    val key = KEY.find(tag)?.groupValues?.get(2)?.lowercase() ?: continue
    if (key !in WANTED) continue
    val content = CONTENT.find(tag)?.groupValues?.get(2)?.trim()?.replace("&amp;", "&") ?: continue
    resolvedImage(content, pageUrl)?.let { return it }
  }
  return null
}

/** The first picture in a page sent as Markdown, as Firecrawl and Tavily send them. */
fun markdownImage(markdown: String, pageUrl: String): String? =
  MARKDOWN_IMAGE.findAll(markdown.take(HEAD_BYTES)).firstNotNullOfOrNull { resolvedImage(it.groupValues[1], pageUrl) }

/** A usable address for a picture, or null for icons, vector logos and anything not on the web. */
internal fun resolvedImage(address: String, pageUrl: String): String? {
  if (address.isBlank() || address.startsWith("data:")) return null
  val resolved = runCatching { URI(pageUrl).resolve(address.replace(" ", "%20")).toString() }.getOrNull() ?: return null
  if (!resolved.startsWith("https://") && !resolved.startsWith("http://")) return null
  val path = resolved.substringBefore('?').lowercase()
  if (NOT_PICTURES.any { path.endsWith(it) }) return null
  if (ICONS.any { path.contains(it) }) return null
  return resolved
}

/** The preview image is in the head, near the top; reading further only costs time. */
private const val HEAD_BYTES = 60_000
private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
private val KEY = Regex("(property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val CONTENT = Regex("(content)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val WANTED = setOf("og:image", "og:image:secure_url", "og:image:url", "twitter:image", "twitter:image:src")
private val MARKDOWN_IMAGE = Regex("!\\[[^\\]]*]\\(\\s*([^)\\s]+)")
private val NOT_PICTURES = listOf(".svg", ".ico")
private val ICONS = listOf("favicon", "/logo", "logo.", "sprite", "pixel", "1x1", "spacer")
