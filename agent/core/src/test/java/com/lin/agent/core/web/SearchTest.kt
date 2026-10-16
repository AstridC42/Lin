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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

  @Test
  fun `nothing stored means no provider`() {
    assertEquals(SearchProvider.NONE, searchProviderNamed(null))
    assertEquals(SearchProvider.NONE, searchProviderNamed(""))
    // A value written by a newer build and read by an older one reads as none rather than
    // throwing on a screen somebody is trying to open.
    assertEquals(SearchProvider.NONE, searchProviderNamed("KAGI"))
  }

  @Test
  fun `a stored provider reads back`() {
    assertEquals(SearchProvider.SEARXNG, searchProviderNamed("searxng"))
    assertEquals(SearchProvider.BRAVE, searchProviderNamed(" BRAVE "))
  }

  @Test
  fun `searxng asks its own address for json`() {
    val (url, headers) = searchRequest(SearchProvider.SEARXNG, "kotlin flows", "https://s.example.com/", "")!!
    assertEquals("https://s.example.com/search?q=kotlin%20flows&format=json", url)
    assertEquals("application/json", headers["Accept"])
  }

  @Test
  fun `brave carries its key in the documented header`() {
    val (url, headers) = searchRequest(SearchProvider.BRAVE, "kotlin", "", "secret-key", count = 4)!!
    assertTrue(url, url.startsWith("https://api.search.brave.com/res/v1/web/search?q=kotlin"))
    assertTrue(url, url.contains("count=4"))
    assertEquals("secret-key", headers["X-Subscription-Token"])
  }

  @Test
  fun `no request asks for an encoding it would have to undo itself`() {
    // HttpURLConnection asks for gzip and decompresses transparently, but only while the
    // caller has not set the header. Setting it hands back compressed bytes that the JSON
    // parser reads as nothing at all - a failure that looks like an empty result list.
    for (provider in listOf(SearchProvider.SEARXNG, SearchProvider.BRAVE)) {
      val headers = searchRequest(provider, "x", "https://s", "k")!!.second
      assertFalse("$provider", headers.keys.any { it.equals("Accept-Encoding", ignoreCase = true) })
    }
  }

  @Test
  fun `brave's count stays inside what it accepts`() {
    assertTrue(searchRequest(SearchProvider.BRAVE, "x", "", "k", count = 99)!!.first.contains("count=20"))
    assertTrue(searchRequest(SearchProvider.BRAVE, "x", "", "k", count = 0)!!.first.contains("count=1"))
  }

  @Test
  fun `a provider with nothing configured makes no request`() {
    assertNull(searchRequest(SearchProvider.NONE, "x", "https://s", "k"))
    assertNull(searchRequest(SearchProvider.SEARXNG, "x", "", "k"))
    assertNull(searchRequest(SearchProvider.BRAVE, "x", "https://s", ""))
  }

  @Test
  fun `an empty question is not asked`() {
    assertNull(searchRequest(SearchProvider.SEARXNG, "   ", "https://s", ""))
  }

  @Test
  fun `a space is never a plus sign`() {
    // Some engines take a literal + in the query, and the search then finds nothing for a
    // reason nobody can see.
    val url = searchRequest(SearchProvider.SEARXNG, "a b", "https://s", "")!!.first
    assertFalse(url, url.contains("a+b"))
    assertTrue(url, url.contains("a%20b"))
  }

  @Test
  fun `awkward characters in a query are escaped`() {
    val url = searchRequest(SearchProvider.SEARXNG, "c++ & \"rust\"", "https://s", "")!!.first
    assertFalse(url, url.contains("&q"))
    assertTrue(url, url.contains("%26"))
    assertTrue(url, url.contains("%22"))
  }

  @Test
  fun `searxng results are read`() {
    val body =
      """
      {"query":"kotlin","results":[
        {"title":"Kotlin","url":"https://kotlinlang.org","content":"A modern language."},
        {"title":"Docs","url":"https://kotlinlang.org/docs","content":"Reference."}]}
      """
        .trimIndent()
    val hits = hitsFrom(SearchProvider.SEARXNG, body)
    assertEquals(2, hits.size)
    assertEquals("Kotlin", hits.first().title)
    assertEquals("https://kotlinlang.org", hits.first().url)
    assertEquals("A modern language.", hits.first().snippet)
  }

  @Test
  fun `a searxng direct answer comes first and keeps no url`() {
    // For a conversion or a definition the answer is the point and the links are beside it.
    val body = """{"answers":["42 kg is 92.6 lb"],"results":[{"title":"t","url":"https://x","content":"c"}]}"""
    val hits = hitsFrom(SearchProvider.SEARXNG, body)
    assertEquals("42 kg is 92.6 lb", hits.first().snippet)
    assertEquals("", hits.first().url)
  }

  @Test
  fun `a searxng answer sent as an object is read too`() {
    // Older instances send strings here and newer ones send objects; both are in the wild.
    val body = """{"answers":[{"answer":"92.6 lb"}],"results":[]}"""
    assertEquals("92.6 lb", hitsFrom(SearchProvider.SEARXNG, body).first().snippet)
  }

  @Test
  fun `brave results are read`() {
    val body =
      """{"web":{"results":[{"title":"Kotlin","url":"https://kotlinlang.org","description":"A language."}]}}"""
    val hits = hitsFrom(SearchProvider.BRAVE, body)
    assertEquals(1, hits.size)
    assertEquals("A language.", hits.first().snippet)
  }

  @Test
  fun `the same page twice is one result`() {
    val body =
      """{"results":[{"title":"a","url":"https://x","content":"1"},{"title":"b","url":"https://x","content":"2"}]}"""
    assertEquals(1, hitsFrom(SearchProvider.SEARXNG, body).size)
  }

  @Test
  fun `only as many results as were asked for`() {
    val many = (1..20).joinToString(",") { """{"title":"t$it","url":"https://x/$it","content":"c"}""" }
    assertEquals(3, hitsFrom(SearchProvider.SEARXNG, """{"results":[$many]}""", limit = 3).size)
  }

  @Test
  fun `an unreadable body is no results rather than a crash`() {
    // A SearXNG with json not enabled answers 403 with an HTML page, and that body reaches
    // this function.
    assertTrue(hitsFrom(SearchProvider.SEARXNG, "<html>403 Forbidden</html>").isEmpty())
    assertTrue(hitsFrom(SearchProvider.BRAVE, "").isEmpty())
    assertTrue(hitsFrom(SearchProvider.SEARXNG, "{}").isEmpty())
  }

  @Test
  fun `results are written with the address on its own line`() {
    // The next thing a model does with a promising result is ask to read it, and a URL
    // buried in a sentence is one it copies out wrongly.
    val said =
      hitsInWords("kotlin", listOf(SearchHit("Kotlin", "https://kotlinlang.org", "A language.")))
    assertTrue(said, said.contains("\nhttps://kotlinlang.org\n"))
    assertTrue(said, said.contains("1. Kotlin"))
  }

  @Test
  fun `nothing found says so plainly`() {
    assertTrue(hitsInWords("zxqw", emptyList()).contains("Nothing came back"))
  }

  @Test
  fun `a result with no title falls back to its address`() {
    assertTrue(hitsInWords("q", listOf(SearchHit("", "https://x", ""))).contains("https://x"))
  }
}

class SearchEmptyTest {

  @Test
  fun `an empty search never says the thing does not exist`() {
    // The bug this exists for: with no results Lin said "it's possible there aren't any
    // official Doctor Who Lego sets" - a claim about the world built from a broken search.
    val said = nothingCameBack(SearchProvider.BRAVE, """{"web":{"results":[]}}""")
    assertTrue(said, said.contains("do not tell them"))
    assertTrue(said, said.contains("does not exist"))
    assertTrue(said, said.contains("brave"))
  }

  @Test
  fun `what came back is named, so the fault can be found`() {
    assertTrue(nothingCameBack(SearchProvider.SEARXNG, "").contains("an empty reply"))
    assertTrue(
      nothingCameBack(SearchProvider.SEARXNG, """{"results":[]}""").contains("no results in it")
    )
    // An array counts as data too. A service that answers `[]` has given an empty answer,
    // not a web page, and telling somebody to go and look at their JSON settings over it
    // would send them after the wrong fault.
    assertTrue(nothingCameBack(SearchProvider.BRAVE, "[]").contains("no results in it"))
    val page = nothingCameBack(SearchProvider.SEARXNG, "<!DOCTYPE html><html><head>")
    assertTrue(page, page.contains("a page rather than data"))
    assertTrue(page, page.contains("<!DOCTYPE html>"))
  }

  @Test
  fun `the trial report is a fact rather than a verdict`() {
    // What somebody can read out, or screenshot, and have it mean something.
    val page = "<!DOCTYPE html><html><head>"
    assertEquals(
      "HTTP 200 · 0 results · ${page.length} bytes starting \"$page\"",
      searchTrialReport(200, 0, page),
    )
    assertEquals("HTTP 403 · 0 results · 0 bytes", searchTrialReport(403, 0, ""))
    assertEquals("HTTP 200 · 1 result · 2 bytes starting \"{}\"", searchTrialReport(200, 1, "{}"))
  }

  @Test
  fun `a long first line is cut rather than filling the screen`() {
    val report = searchTrialReport(200, 0, "x".repeat(500))
    assertTrue(report, report.contains("500 bytes"))
    assertTrue(report, report.contains("x".repeat(40)))
    assertTrue(report, !report.contains("x".repeat(41)))
  }
}
