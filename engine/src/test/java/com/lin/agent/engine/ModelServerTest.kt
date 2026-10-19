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

package com.lin.agent.engine

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Talking to an Ollama server, without one.
 *
 * The failure paths get as much attention as the success path, and on purpose: this screen is
 * where somebody discovers their phone cannot reach their server, from a phone, with no logcat and
 * no terminal. A probe that fails with the wrong explanation sends them to fix the wrong thing.
 */
class ModelServerTest {

  // ---- the address ----

  /** Typing a LAN address from memory produces no scheme and no port. Both are assumed. */
  @Test
  fun aBareAddressGetsASchemeAndOllamasPort() {
    assertEquals("http://192.168.1.50:11434/api/tags", tagsUrl("192.168.1.50"))
  }

  @Test
  fun anExplicitPortIsKept() {
    assertEquals("http://192.168.1.50:8080/api/tags", tagsUrl("http://192.168.1.50:8080"))
  }

  /**
   * Under https something is terminating TLS, and that listens on 443.
   *
   * Adding Ollama's port to a tunnel hostname would turn a working address into a hang, which is
   * the failure that looks most like the tunnel being down.
   */
  @Test
  fun httpsIsLeftOnItsOwnPort() {
    assertEquals("https://lin.example.com/api/tags", tagsUrl("https://lin.example.com"))
  }

  @Test
  fun aTrailingSlashDoesNotDoubleUp() {
    assertEquals("http://host:11434/api/tags", tagsUrl("http://host:11434/"))
    assertEquals("http://host:11434/api/tags", tagsUrl("http://host:11434///"))
  }

  /** Pasting from Ollama's own documentation brings the API path along with it. */
  @Test
  fun anAlreadyAttachedApiPathIsNotAskedForTwice() {
    assertEquals("http://host:11434/api/tags", tagsUrl("http://host:11434/api/tags"))
    assertEquals("http://host:11434/api/tags", tagsUrl("http://host:11434/api"))
    assertEquals("http://host:11434/api/tags", tagsUrl("http://host:11434/api/tags/"))
  }

  /** A proxy may serve Ollama under a path, which has to survive. */
  @Test
  fun aPathPrefixIsKept() {
    assertEquals("https://lin.example.com/ollama/api/tags", tagsUrl("https://lin.example.com/ollama"))
  }

  @Test
  fun whitespaceIsTrimmedRatherThanSent() {
    assertEquals("http://host:11434/api/tags", tagsUrl("  host  "))
  }

  @Test
  fun nothingTypedIsNoAddress() {
    assertNull(tagsUrl(""))
    assertNull(tagsUrl("   "))
  }

  // ---- the headers ----

  @Test
  fun oneHeaderIsParsedOnTheFirstColon() {
    // The value contains a colon of its own, which must not split it.
    assertEquals(
      mapOf("Authorization" to "Bearer abc:def"),
      headersFrom("Authorization: Bearer abc:def"),
    )
  }

  /**
   * Two headers, which is the case the field was added for.
   *
   * A Cloudflare Access service token is an id and a secret, sent as separate headers. A field
   * that held one header would have failed the one scenario it exists to serve.
   */
  @Test
  fun aCloudflareServiceTokenIsTwoHeaders() {
    val parsed =
      headersFrom(
        """
        CF-Access-Client-Id: abc123.access
        CF-Access-Client-Secret: shhh
        """
          .trimIndent()
      )
    assertEquals(2, parsed.size)
    assertEquals("abc123.access", parsed["CF-Access-Client-Id"])
    assertEquals("shhh", parsed["CF-Access-Client-Secret"])
  }

  @Test
  fun blankAndMalformedLinesAreDropped() {
    val parsed = headersFrom("\n  \nAuthorization: Bearer x\nnot a header\n: novalue\nName:\n")
    assertEquals(mapOf("Authorization" to "Bearer x"), parsed)
  }

  @Test
  fun noHeaderFieldMeansNoHeaders() {
    assertTrue(headersFrom("").isEmpty())
  }

  // ---- the answer ----

  @Test
  fun modelsAreReadWithTheirDetailsAndSortedByName() {
    val models = parseModels(TAGS_BODY)
    assertEquals(listOf("gemma3:12b", "qwen3:32b"), models.map { it.name })

    val qwen = models.last()
    assertEquals(20_000_000_000L, qwen.sizeBytes)
    assertEquals("32.8B", qwen.parameterSize)
    assertEquals("Q4_K_M", qwen.quantization)
  }

  /** An older server sends no details block. A picker without a quant label still works. */
  @Test
  fun aModelWithNoDetailsIsStillUsable() {
    val models = parseModels("""{"models":[{"name":"llama3:8b","size":4661224676}]}""")
    assertEquals(1, models.size)
    assertEquals("llama3:8b", models[0].name)
    assertEquals(4_661_224_676L, models[0].sizeBytes)
    assertEquals("", models[0].parameterSize)
  }

  /** `model` rather than `name`, which costs one line of fallback against a field rename. */
  @Test
  fun theModelFieldStandsInForAMissingName() {
    assertEquals(listOf("mistral:7b"), parseModels("""{"models":[{"model":"mistral:7b"}]}""").map { it.name })
  }

  @Test
  fun anEntryWithNoUsableNameIsDropped() {
    assertTrue(parseModels("""{"models":[{"size":1},{"name":""}]}""").isEmpty())
  }

  @Test
  fun aServerWithNothingPulledReadsAsAnEmptyList() {
    assertTrue(parseModels("""{"models":[]}""").isEmpty())
    assertTrue("a missing models key is not a crash", parseModels("""{}""").isEmpty())
  }

  // ---- what failures say ----

  /**
   * The failure that is invisible from the phone, so the message has to carry the answer.
   *
   * Ollama binds to localhost unless told otherwise. The server is running, the address is right,
   * and it still refuses - and nothing about "connection refused" suggests OLLAMA_HOST.
   */
  @Test
  fun aRefusedConnectionNamesTheBindAddress() {
    val message = describeFailure(ConnectException("Connection refused"))
    assertTrue(message, message.contains("OLLAMA_HOST=0.0.0.0"))
  }

  @Test
  fun aTimeoutAndAnUnknownHostAreDistinguished() {
    assertTrue(describeFailure(SocketTimeoutException()).contains("ten seconds"))
    assertTrue(describeFailure(UnknownHostException("nope")).contains("Could not find"))
  }

  /** A refusal points at the header field, which is the only thing the user can change. */
  @Test
  fun aRefusalPointsAtTheHeaderField() {
    for (status in listOf(401, 403)) {
      assertTrue(status.toString(), describeStatus(status).contains("header"))
    }
  }

  /**
   * A 404 names the two things it can be, and neither of them is "Ollama".
   *
   * It said "no Ollama API there" when Ollama was the only kind of server this spoke to. Now
   * it is one of three, and the likelier cause on the other two is a server that simply does
   * not list its models.
   */
  @Test
  fun aNotFoundSaysItReachedSomethingWithoutBlamingOllama() {
    val message = describeStatus(404)
    assertTrue(message, message.contains("404"))
    assertFalse(message, message.contains("Ollama"))
  }

  /** A 502 through a tunnel means the tunnel is up and the thing behind it is not. */
  @Test
  fun aServerErrorSeparatesTheTunnelFromOllama() {
    assertTrue(describeStatus(502).contains("tunnel is up"))
  }

  // ---- end to end, without a network ----

  @Test
  fun aGoodAnswerBecomesAList() = runTest {
    val result = ModelServer { _, _ -> response(200, TAGS_BODY) }.models("host")
    assertEquals(
      listOf("gemma3:12b", "qwen3:32b"),
      (result as ServerModelsResult.Found).models.map { it.name },
    )
  }

  @Test
  fun theHeadersAndTheAddressBothReachTheConnection() = runTest {
    var seenUrl: String? = null
    var seenHeaders: Map<String, String> = emptyMap()
    val server =
      ModelServer { url, headers ->
        seenUrl = url
        seenHeaders = headers
        response(200, """{"models":[]}""")
      }

    server.models("  lin.example.com:11434/api  ", "Authorization: Bearer tok")

    assertEquals("http://lin.example.com:11434/api/tags", seenUrl)
    assertEquals(mapOf("Authorization" to "Bearer tok"), seenHeaders)
  }

  /**
   * Something answered, but not Ollama - a router's admin page, most likely.
   *
   * The message now quotes what came back as well as saying it was not understood, because
   * the sentence alone left nobody any way to find out what had actually answered.
   */
  @Test
  fun anHtmlAnswerIsReportedAsNotOllamaAndQuotedBack() = runTest {
    val result = ModelServer { _, _ -> response(200, "<html><body>Router</body></html>") }
      .models("host")
    val message = (result as ServerModelsResult.Failed).message
    assertTrue(message, message.startsWith(ModelServer.NOT_OLLAMA))
    assertTrue(message, message.contains("Router"))
  }

  @Test
  fun aThrownConnectionBecomesASentence() = runTest {
    val result = ModelServer { _, _ -> throw ConnectException("Connection refused") }.models("host")
    assertTrue((result as ServerModelsResult.Failed).message.contains("OLLAMA_HOST"))
  }

  @Test
  fun noAddressIsItsOwnMessageRatherThanAConnectionAttempt() = runTest {
    var opened = false
    val result = ModelServer { _, _ -> opened = true; response(200, "{}") }.models("")
    assertEquals(ModelServer.NO_ADDRESS, (result as ServerModelsResult.Failed).message)
    assertTrue("nothing should be dialled with no address", !opened)
  }

  /** The response is closed even when its body is unusable, so the connection is not leaked. */
  @Test
  fun theResponseIsAlwaysClosed() = runTest {
    var closed = false
    ModelServer { _, _ -> response(200, "not json", onClose = { closed = true }) }.models("host")
    assertTrue(closed)
  }

  private fun response(status: Int, body: String, onClose: () -> Unit = {}) =
    object : ModelServer.Response {
      override val status = status
      override val body = body

      override fun close() = onClose()
    }

  private companion object {
    val TAGS_BODY =
      """
      {"models":[
        {"name":"qwen3:32b","model":"qwen3:32b","size":20000000000,
         "details":{"family":"qwen3","parameter_size":"32.8B","quantization_level":"Q4_K_M"}},
        {"name":"gemma3:12b","model":"gemma3:12b","size":8100000000,
         "details":{"family":"gemma3","parameter_size":"12.2B","quantization_level":"Q4_K_M"}}
      ]}
      """
        .trimIndent()
  }
}
