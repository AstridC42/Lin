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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format vLLM and Unsloth share.
 *
 * Everything here is a thing that fails silently if it is wrong: a renamed field shows no
 * thinking, an unenforced schema parses to nothing, a mis-parsed delta loses the reply. None of
 * it announces itself, and none of it can be checked without a server - so it is pinned here.
 */
class OpenAiDialectTest {

  private fun body(request: TurnRequest) = JSONObject(openAiRequestBody("qwen3", request))

  private fun ask(
    systemPrompt: String = "",
    tools: List<ToolSpec> = emptyList(),
    responseSchema: String? = null,
    reasoning: Reasoning = Reasoning.OFF,
  ) =
    TurnRequest(
      messages = listOf(Message(Message.Role.USER, "hello")),
      systemPrompt = systemPrompt,
      tools = tools,
      responseSchema = responseSchema,
      reasoning = reasoning,
    )

  // ---- the request ----

  @Test
  fun aConversationIsSentAsRolesAndContent() {
    val sent = body(ask(systemPrompt = "You are Lin."))
    assertEquals("qwen3", sent.getString("model"))
    assertTrue(sent.getBoolean("stream"))
    val messages = sent.getJSONArray("messages")
    assertEquals("system", messages.getJSONObject(0).getString("role"))
    assertEquals("You are Lin.", messages.getJSONObject(0).getString("content"))
    assertEquals("user", messages.getJSONObject(1).getString("role"))
  }

  /** A tool result has to say which tool it came from, and OpenAI's field for that is `name`. */
  @Test
  fun aToolResultCarriesTheToolName() {
    val sent =
      body(
        TurnRequest(
          messages = listOf(Message(Message.Role.TOOL, "It is 4pm.", toolName = "clock"))
        )
      )
    val message = sent.getJSONArray("messages").getJSONObject(0)
    assertEquals("tool", message.getString("role"))
    assertEquals("clock", message.getString("name"))
  }

  /** top_k is not an OpenAI field; a faithful server rejects the whole request over one. */
  @Test
  fun noParameterOutsideTheStandardIsSent() {
    assertFalse(body(ask()).has("top_k"))
  }

  @Test
  fun toolsAreSentAsFunctions() {
    val sent = body(ask(tools = listOf(ToolSpec("call", "Calls somebody", """{"type":"object"}"""))))
    val tool = sent.getJSONArray("tools").getJSONObject(0)
    assertEquals("function", tool.getString("type"))
    assertEquals("call", tool.getJSONObject("function").getString("name"))
    assertTrue(tool.getJSONObject("function").has("parameters"))
  }

  @Test
  fun aSchemaIsSentAsAStrictNamedResponseFormat() {
    val sent = body(ask(responseSchema = """{"type":"object"}"""))
    val format = sent.getJSONObject("response_format")
    assertEquals("json_schema", format.getString("type"))
    val schema = format.getJSONObject("json_schema")
    assertTrue(schema.getBoolean("strict"))
    assertEquals("object", schema.getJSONObject("schema").getString("type"))
  }

  // ---- pictures ----

  @Test
  fun aPictureTravelsAsADataUriBesideTheText() {
    val sent =
      body(
        TurnRequest(
          messages =
            listOf(
              Message(
                Message.Role.USER,
                "What is this?",
                attachments = listOf(Attachment.Picture("a.jpg", byteArrayOf(1, 2, 3))),
              )
            )
        )
      )
    val content = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
    assertEquals("image_url", content.getJSONObject(0).getString("type"))
    assertTrue(
      content.getJSONObject(0).getJSONObject("image_url").getString("url")
        .startsWith("data:image/jpeg;base64,")
    )
    assertEquals("What is this?", content.getJSONObject(1).getString("text"))
  }

  /**
   * A plain message keeps a plain string.
   *
   * Some servers reject a content list on a message with no parts in it, and there is nothing
   * to gain by sending one.
   */
  @Test
  fun aMessageWithNoPictureKeepsAPlainStringContent() {
    val sent = body(ask())
    assertEquals("hello", sent.getJSONArray("messages").getJSONObject(0).getString("content"))
  }

  /** The default base64 wraps every 76 characters, which is valid base64 and invalid JSON. */
  @Test
  fun theEncodedPictureHasNoLineBreaksInIt() {
    val sent =
      body(
        TurnRequest(
          messages =
            listOf(
              Message(
                Message.Role.USER,
                "x",
                attachments = listOf(Attachment.Picture("a.jpg", ByteArray(500) { 7 })),
              )
            )
        )
      )
    val url =
      sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        .getJSONObject("image_url")
        .getString("url")
    assertFalse(url.contains("\n"))
  }

  @Test
  fun ollamaTakesPicturesAsBareBase64Instead() {
    val sent =
      org.json.JSONObject(
        chatRequestBody(
          "gemma4",
          TurnRequest(
            messages =
              listOf(
                Message(
                  Message.Role.USER,
                  "What is this?",
                  attachments = listOf(Attachment.Picture("a.jpg", byteArrayOf(1, 2, 3))),
                )
              )
          ),
        )
      )
    val message = sent.getJSONArray("messages").getJSONObject(0)
    assertEquals("What is this?", message.getString("content"))
    assertEquals(1, message.getJSONArray("images").length())
  }

  // ---- thinking, which is not an OpenAI field ----

  @Test
  fun thinkingIsAskedForThroughTheChatTemplate() {
    val sent = body(ask(reasoning = Reasoning.THOROUGH))
    assertTrue(sent.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
  }

  @Test
  fun anOrdinaryTurnSaysNothingAboutThinking() {
    assertFalse(body(ask()).has("chat_template_kwargs"))
  }

  /**
   * A structured turn says thinking is off, out loud.
   *
   * The only place this sends something it could have omitted. vLLM has a known bug where a
   * schema is not enforced while a reasoning parser is active - the output escapes into the
   * unconstrained reasoning channel - and turning thinking off explicitly is the documented way
   * around it. This app never thinks on a structured turn anyway, so it costs nothing.
   */
  @Test
  fun aStructuredTurnTurnsThinkingOffExplicitly() {
    val sent = body(ask(responseSchema = """{"type":"object"}""", reasoning = Reasoning.THOROUGH))
    assertTrue(sent.has("chat_template_kwargs"))
    assertFalse(sent.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
  }

  // ---- the stream ----

  @Test
  fun aDeltaIsReadAsText() {
    val chunk = readOpenAiChunk("""data: {"choices":[{"delta":{"content":"Hel"}}]}""")
    assertEquals("Hel", chunk?.text)
    assertFalse(chunk?.done == true)
  }

  @Test
  fun theDoneSentinelEndsTheStream() {
    assertTrue(readOpenAiChunk("data: [DONE]")?.done == true)
  }

  /** Not every server sends [DONE], so a reply must not depend on it arriving. */
  @Test
  fun aFinishReasonAlsoEndsTheStream() {
    val chunk = readOpenAiChunk("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")
    assertTrue(chunk?.done == true)
  }

  @Test
  fun aChunkThatHasNotFinishedIsNotTreatedAsFinished() {
    val chunk = readOpenAiChunk("""data: {"choices":[{"delta":{"content":"x"}}]}""")
    assertFalse(chunk?.done == true)
  }

  @Test
  fun blankLinesBetweenEventsAreIgnored() {
    assertNull(readOpenAiChunk(""))
    assertNull(readOpenAiChunk("   "))
  }

  /**
   * The field was renamed, so both names are read.
   *
   * Older vLLM returns `reasoning_content`; newer examples use `reasoning`. Reading one would
   * show no thinking at all on half the installations in the wild, and which half would depend
   * on when somebody happened to install it.
   */
  @Test
  fun thinkingIsReadUnderEitherOfItsNames() {
    assertEquals(
      "Working.",
      readOpenAiChunk("""data: {"choices":[{"delta":{"reasoning_content":"Working."}}]}""")?.thinking,
    )
    assertEquals(
      "Working.",
      readOpenAiChunk("""data: {"choices":[{"delta":{"reasoning":"Working."}}]}""")?.thinking,
    )
  }

  @Test
  fun thinkingIsNotMixedIntoTheAnswer() {
    val chunk =
      readOpenAiChunk("""data: {"choices":[{"delta":{"content":"42.","reasoning":"Count."}}]}""")
    assertEquals("42.", chunk?.text)
    assertEquals("Count.", chunk?.thinking)
  }

  // ---- tool calls ----

  /** `arguments` is a JSON string here, not an object. Parsing it is not optional. */
  @Test
  fun toolArgumentsAreParsedOutOfTheirString() {
    val chunk =
      readOpenAiChunk(
        """data: {"choices":[{"delta":{"tool_calls":[{"function":{"name":"call","arguments":"{\"who\":\"Ben\"}"}}]}}]}"""
      )
    assertEquals("call", chunk?.toolCalls?.single()?.name)
    assertEquals("Ben", chunk?.toolCalls?.single()?.arguments?.get("who"))
  }

  /** A fragment with no name yet cannot be looked up; the delta carrying it is the one. */
  @Test
  fun aToolCallFragmentWithoutANameIsSkipped() {
    val chunk =
      readOpenAiChunk("""data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{}"}}]}}]}""")
    assertTrue(chunk?.toolCalls?.isEmpty() == true)
  }

  /**
   * Unparseable arguments still produce the call.
   *
   * The loop then runs the tool and the tool says what it needed, which a model can act on -
   * better than a request it made being silently ignored.
   */
  @Test
  fun aCallWithBrokenArgumentsStillReachesTheLoop() {
    val chunk =
      readOpenAiChunk(
        """data: {"choices":[{"delta":{"tool_calls":[{"function":{"name":"call","arguments":"{not json"}}]}}]}"""
      )
    assertEquals("call", chunk?.toolCalls?.single()?.name)
    assertTrue(chunk?.toolCalls?.single()?.arguments?.isEmpty() == true)
  }

  // ---- errors ----

  @Test
  fun anErrorObjectIsReadWhetherOrNotItArrivedAsAnEvent() {
    assertEquals(
      "That model does not exist.",
      readOpenAiChunk("""{"error":{"message":"That model does not exist."}}""")?.error,
    )
    assertEquals(
      "Out of memory.",
      readOpenAiChunk("""data: {"error":{"message":"Out of memory."}}""")?.error,
    )
  }

  @Test
  fun somethingUnrecognisableIsIgnoredRatherThanTreatedAsAReply() {
    assertNull(readOpenAiChunk("<html>Router login</html>"))
    assertNull(readOpenAiChunk("data: not json"))
  }

  // ---- the model list ----

  @Test
  fun theModelListIsReadFromTheDataArray() {
    val models =
      parseOpenAiModels(
        """{"object":"list","data":[{"id":"Qwen/Qwen3-32B","object":"model"},{"id":"a-model"}]}"""
      )
    assertEquals(listOf("Qwen/Qwen3-32B", "a-model"), models.map { it.name }.sorted())
  }

  /**
   * An OpenAI list says nothing about size or quantisation, so those stay empty.
   *
   * Better than inventing numbers for a model served out of a directory of safetensors, where
   * "size" is not one meaningful figure.
   */
  @Test
  fun whatTheFormatDoesNotSayIsLeftEmptyRatherThanGuessed() {
    val model = parseOpenAiModels("""{"data":[{"id":"m"}]}""").single()
    assertEquals(0L, model.sizeBytes)
    assertEquals("", model.parameterSize)
    assertEquals("", model.quantization)
  }

  /**
   * "OpenAI-compatible" is a family, not a specification.
   *
   * The standard is a `data` array; real servers also send `models`, and some send the array
   * on its own. Requiring one of them produced "something answered, but not the way this
   * expects" against a server that was working perfectly well.
   */
  @Test
  fun theModelListIsReadInAnyOfTheShapesTheseServersSend() {
    assertEquals(listOf("a"), parseOpenAiModels("""{"data":[{"id":"a"}]}""").map { it.name })
    assertEquals(listOf("a"), parseOpenAiModels("""{"models":[{"id":"a"}]}""").map { it.name })
    assertEquals(listOf("a"), parseOpenAiModels("""[{"id":"a"}]""").map { it.name })
  }

  @Test
  fun somethingWithNoModelListInItIsStillRejected() {
    assertThrows(org.json.JSONException::class.java) { parseOpenAiModels("""{"object":"list"}""") }
    assertThrows(org.json.JSONException::class.java) { parseOpenAiModels("<html>Login</html>") }
  }

  /**
   * The message has to carry what came back.
   *
   * Without it there is no way for anyone - the user or whoever they ask - to find out what
   * answered. A proxy's login page, an error envelope and an empty body all produce the same
   * sentence otherwise.
   */
  @Test
  fun anUnrecognisedAnswerIsQuotedBackSoItCanBeDiagnosed() {
    val said = ModelServer.wrongDialect(ServerKind.UNSLOTH, """{"detail":"Not Found"}""")
    assertTrue(said, said.contains("Not Found"))
  }

  @Test
  fun anEmptyAnswerSaysThatRatherThanQuotingNothing() {
    val said = ModelServer.wrongDialect(ServerKind.VLLM, "   ")
    assertTrue(said, said.contains("empty"))
  }

  @Test
  fun aVeryLongAnswerIsCutRatherThanFillingTheScreen() {
    val said = ModelServer.wrongDialect(ServerKind.VLLM, "x".repeat(5000))
    assertTrue(said.length < 500)
  }

  /** A multi-line HTML page has to stay one paragraph on a phone. */
  @Test
  fun aQuotedAnswerIsOneLine() {
    val said = ModelServer.wrongDialect(ServerKind.VLLM, "<html>\n  <body>\n Login\n</body>")
    assertFalse(said.contains("\n  "))
  }

  // ---- what a refusal means, per kind ----

  /**
   * 401 means something different on each, and the old wording named only Cloudflare.
   *
   * On an Unsloth server a refusal almost always means the key is not in the header field, and
   * being sent to look at a proxy instead is a wasted evening.
   */
  @Test
  fun aRefusalNamesTheThingThatWouldActuallyFixIt() {
    val unsloth = describeStatus(401, ServerKind.UNSLOTH)
    assertTrue(unsloth, unsloth.contains("sk-unsloth"))
    assertTrue(unsloth, unsloth.contains("Authorization: Bearer"))

    val vllm = describeStatus(401, ServerKind.VLLM)
    assertTrue(vllm, vllm.contains("--api-key"))

    assertTrue(describeStatus(401, ServerKind.OLLAMA).contains("Cloudflare"))
  }

  /** The 404 text named Ollama, which is wrong for two of the three kinds now. */
  @Test
  fun aMissingPathDoesNotBlameOllamaOnAServerThatIsNotOllama() {
    assertFalse(describeStatus(404, ServerKind.VLLM).contains("Ollama"))
    assertTrue(describeStatus(404, ServerKind.VLLM).contains("type the"))
  }

  @Test
  fun eachKindIsToldApartByItsOwnMessage() {
    val messages = ServerKind.entries.map { ModelServer.wrongDialect(it) }
    assertEquals(messages.size, messages.toSet().size)
    assertTrue(ModelServer.wrongDialect(ServerKind.VLLM).contains("8000"))
    assertTrue(ModelServer.wrongDialect(ServerKind.UNSLOTH).contains("key"))
  }
}
