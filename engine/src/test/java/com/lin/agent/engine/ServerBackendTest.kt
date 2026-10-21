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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A turn against the user's own server, without one. */
class ServerBackendTest {

  // ---- the request ----

  @Test
  fun theRequestNamesTheModelAndStreams() {
    val body =
      JSONObject(
        chatRequestBody("qwen3:32b", TurnRequest(messages = listOf(user("hello"))))
      )
    assertEquals("qwen3:32b", body.getString("model"))
    assertTrue("streaming is what makes tokens appear as they arrive", body.getBoolean("stream"))
    assertEquals(0.0, body.getJSONObject("options").getDouble("temperature"), 0.0)
  }

  @Test
  fun theSystemPromptGoesFirstAndTheConversationFollowsInOrder() {
    val body =
      JSONObject(
        chatRequestBody(
          "m",
          TurnRequest(
            messages = listOf(user("one"), assistant("two"), user("three")),
            systemPrompt = "be brief",
          ),
        )
      )
    val messages = body.getJSONArray("messages")
    assertEquals(4, messages.length())
    assertEquals(
      listOf("system", "user", "assistant", "user"),
      (0 until messages.length()).map { messages.getJSONObject(it).getString("role") },
    )
    assertEquals("be brief", messages.getJSONObject(0).getString("content"))
    assertEquals("three", messages.getJSONObject(3).getString("content"))
  }

  /** Built with a JSON library rather than string templates, so what the user typed survives. */
  @Test
  fun quotesAndNewlinesInAMessageSurviveIntact() {
    val awkward = "she said \"no\"\nthen \\ left\ttab"
    val body = JSONObject(chatRequestBody("m", TurnRequest(messages = listOf(user(awkward)))))
    assertEquals(awkward, body.getJSONArray("messages").getJSONObject(0).getString("content"))
  }

  /** The context window belongs to whoever configured the server, so nothing overrides it. */
  @Test
  fun theContextWindowIsNotOverridden() {
    val options = JSONObject(chatRequestBody("m", TurnRequest(messages = listOf(user("x")))))
      .getJSONObject("options")
    assertFalse("num_ctx is the server's business", options.has("num_ctx"))
  }

  // ---- reading the stream ----

  @Test
  fun aContentChunkIsText() {
    val chunk = readChunk("""{"message":{"role":"assistant","content":"Hel"},"done":false}""")
    assertEquals("Hel", chunk?.text)
    assertFalse(chunk!!.done)
  }

  /**
   * A final chunk may carry content as well as the flag.
   *
   * Treating them as alternatives would drop the last fragment of every reply on a server that
   * sends one, which is the kind of bug that looks like the model trailing off.
   */
  @Test
  fun aFinalChunkKeepsItsContentAndItsFlag() {
    val chunk = readChunk("""{"message":{"content":"lo."},"done":true}""")
    assertEquals("lo.", chunk?.text)
    assertTrue(chunk!!.done)
  }

  @Test
  fun anErrorLineIsAnError() {
    assertEquals("model not found", readChunk("""{"error":"model not found"}""")?.error)
  }

  @Test
  fun blankAndUnparseableLinesAreSkippedRatherThanFatal() {
    assertNull(readChunk(""))
    assertNull(readChunk("   "))
    assertNull(readChunk("<html>"))
  }

  // ---- a whole turn ----

  @Test
  fun tokensArriveInOrderAndTheAnswerIsAssembled() = runTest {
    val events = backend(stream(200, CHUNKS)).converse(request()).toList()

    assertEquals(
      listOf("The ", "answer", "."),
      events.filterIsInstance<TurnEvent.Token>().map { it.text },
    )
    assertEquals("The answer.", (events.last() as TurnEvent.Done).fullText)
  }

  /** Cloudflare's 524 while the model loads: tried once more, and the second answer is used. */
  @Test
  fun aGatewayTimeoutIsTriedOnceMore() = runTest {
    var calls = 0
    val events =
      ServerBackend("host", "", "m", openStream = { _, _, _ -> calls++; if (calls == 1) stream(524, emptyList()) else stream(200, CHUNKS) })
        .converse(request())
        .toList()
    assertEquals(2, calls)
    assertEquals("The answer.", (events.last() as TurnEvent.Done).fullText)
  }

  @Test
  fun aSecondGatewayTimeoutIsExplained() = runTest {
    var calls = 0
    val events =
      ServerBackend("host", "", "m", openStream = { _, _, _ -> calls++; stream(524, emptyList()) }).converse(request()).toList()
    assertEquals(2, calls)
    assertTrue((events.last() as TurnEvent.Failed).message.contains("100 seconds"))
  }

  @Test
  fun theModelIsKeptLoaded() {
    assertEquals("30m", JSONObject(chatRequestBody("m", request())).getString("keep_alive"))
  }

  @Test
  fun visionIsReadFromTheServerOrTheName() {
    assertEquals(true, showSaysVision("""{"capabilities":["completion","vision","tools"]}"""))
    assertEquals(false, showSaysVision("""{"capabilities":["completion","tools"]}"""))
    assertEquals(true, showSaysVision("""{"projector_info":{"clip.has_vision_encoder":true}}"""))
    assertNull(showSaysVision("""{"details":{"family":"llama"}}"""))
    assertTrue(nameSuggestsVision("gemma-4-12B"))
    assertTrue(nameSuggestsVision("gemma3:27b"))
    assertTrue(nameSuggestsVision("qwen2.5-vl:7b"))
    assertFalse(nameSuggestsVision("gemma3:1b"))
    assertFalse(nameSuggestsVision("qwen3:32b"))
  }

  /** A reply cut short is still a reply; discarding it would lose what the user already read. */
  @Test
  fun aStreamThatEndsWithoutTheDoneFlagKeepsWhatArrived() = runTest {
    val events =
      backend(stream(200, listOf("""{"message":{"content":"half a th"}}"""))).converse(request())
        .toList()
    assertEquals("half a th", (events.last() as TurnEvent.Done).fullText)
  }

  /** A removed model is the one server-side failure the user can actually fix. */
  @Test
  fun aMissingModelSaysToPickAnother() = runTest {
    val events =
      backend(stream(200, listOf("""{"error":"model 'qwen3:32b' not found"}""")))
        .converse(request())
        .toList()
    val failure = events.last() as TurnEvent.Failed
    assertTrue(failure.message, failure.message.contains("does not have"))
    assertTrue("the server's own words are kept", failure.detail.contains("not found"))
  }

  @Test
  fun aRefusalPointsAtTheHeaderField() = runTest {
    val events = backend(stream(401, emptyList(), error = "forbidden")).converse(request()).toList()
    val failure = events.single() as TurnEvent.Failed
    assertTrue(failure.message, failure.message.contains("header"))
  }

  /** Something answered 200 and none of it was Ollama - a proxy's page, most likely. */
  @Test
  fun anAnswerThatIsNotOllamaSaysSo() = runTest {
    val events = backend(stream(200, listOf("<html>", "</html>"))).converse(request()).toList()
    assertEquals(ModelServer.NOT_OLLAMA, (events.single() as TurnEvent.Failed).message)
  }

  @Test
  fun aDeadConnectionBecomesASentence() = runTest {
    val backend =
      ServerBackend("host", "", "m", openStream = { _, _, _ -> throw ConnectException("refused") })
    val failure = backend.converse(request()).toList().single() as TurnEvent.Failed
    assertTrue(failure.message, failure.message.contains("OLLAMA_HOST"))
  }

  @Test
  fun noAddressIsSaidRatherThanDialled() = runTest {
    var opened = false
    val backend =
      ServerBackend("", "", "m", openStream = { _, _, _ -> opened = true; stream(200, CHUNKS) })
    assertEquals(
      ModelServer.NO_ADDRESS,
      (backend.converse(request()).toList().single() as TurnEvent.Failed).message,
    )
    assertFalse(opened)
  }

  @Test
  fun theStreamIsClosedWhenTheTurnEnds() = runTest {
    var closed = false
    backend(stream(200, CHUNKS, onClose = { closed = true })).converse(request()).toList()
    assertTrue(closed)
  }

  /** The address, the headers and the body all reach the connection. */
  @Test
  fun theTurnIsSentWhereItWasAimed() = runTest {
    var seenUrl: String? = null
    var seenHeaders: Map<String, String> = emptyMap()
    var seenBody: String? = null
    ServerBackend(
        baseUrl = "  192.168.1.50  ",
        authHeader = "Authorization: Bearer tok",
        modelName = "qwen3:32b",
        openStream = { url, headers, body ->
          seenUrl = url
          seenHeaders = headers
          seenBody = body
          stream(200, CHUNKS)
        },
      )
      .converse(request())
      .toList()

    assertEquals("http://192.168.1.50:11434/api/chat", seenUrl)
    assertEquals(mapOf("Authorization" to "Bearer tok"), seenHeaders)
    assertEquals("qwen3:32b", JSONObject(seenBody!!).getString("model"))
  }

  // ---- reachability ----

  /** Reachable means the server is there *and* still has the chosen model. */
  @Test
  fun aServerWithoutTheChosenModelIsNotReachable() = runTest {
    val tags = """{"models":[{"name":"gemma3:12b"}]}"""
    val withModel = """{"models":[{"name":"qwen3:32b"}]}"""

    assertFalse(reachableAgainst(tags))
    assertTrue(reachableAgainst(withModel))
  }

  private suspend fun reachableAgainst(tagsBody: String): Boolean =
    ServerBackend(
        baseUrl = "host",
        authHeader = "",
        modelName = "qwen3:32b",
        server =
          ModelServer { _, _ ->
            object : ModelServer.Response {
              override val status = 200
              override val body = tagsBody

              override fun close() = Unit
            }
          },
      )
      .reachable()

  // ---- helpers ----

  private fun backend(stream: ServerBackend.Stream) =
    ServerBackend("host", "", "qwen3:32b", openStream = { _, _, _ -> stream })

  private fun request() = TurnRequest(messages = listOf(user("hello")))

  private fun user(text: String) = Message(Message.Role.USER, text)

  private fun assistant(text: String) = Message(Message.Role.ASSISTANT, text)

  private fun stream(
    status: Int,
    lines: List<String>,
    error: String = "",
    onClose: () -> Unit = {},
  ) =
    object : ServerBackend.Stream {
      override val status = status
      override val lines = lines.asSequence()
      override val errorBody = error

      override fun close() = onClose()
    }

  private companion object {
    val CHUNKS =
      listOf(
        """{"message":{"role":"assistant","content":"The "},"done":false}""",
        """{"message":{"role":"assistant","content":"answer"},"done":false}""",
        """{"message":{"role":"assistant","content":"."},"done":false}""",
        """{"message":{"role":"assistant","content":""},"done":true}""",
      )
  }
}
