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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tool handing the model a picture to look at.
 *
 * What a photo search finds is now something the model can be shown rather than only
 * described. The failure to guard against is the one with no symptom: a model that is handed
 * nothing and told nothing describes the picture anyway, from the words that found it.
 */
class ToolPictureTest {

  private val picture = Attachment.Picture("found.jpg", byteArrayOf(1, 2, 3))

  private fun registryReturning(result: ToolResult) =
    ToolRegistry(
      listOf(
        object : AgentTool {
          override val name = "find_photo"
          override val description = "Finds a photo."
          override val parameters = emptyList<ToolParameter>()
          override val roles = setOf(AgentRole.ORCHESTRATOR)

          override suspend fun run(arguments: Map<String, Any?>): ToolResult = result
        }
      )
    )

  /** Reports one tool call, then answers. Records every request so the test can look. */
  private class CallsToolOnce(private val seesPictures: Boolean) : LlmBackend {
    val sent = mutableListOf<TurnRequest>()
    private var asked = false

    override val id = "calls-once"

    override val capabilities =
      BackendCapabilities(
        supportsTools = true,
        supportsImages = seesPictures,
        supportsAudio = false,
        contextWindow = 8192,
      )

    override suspend fun reachable() = true

    override fun converse(request: TurnRequest) =
      kotlinx.coroutines.flow.flow {
        sent.add(request)
        if (!asked) {
          asked = true
          emit(TurnEvent.ToolCalls(listOf(ToolInvocation("find_photo", emptyMap()))))
          emit(TurnEvent.Done(""))
        } else {
          emit(TurnEvent.Done("A sign saying Closed."))
        }
      }
  }

  private suspend fun loopWith(seesPictures: Boolean, result: ToolResult): CallsToolOnce {
    val backend = CallsToolOnce(seesPictures)
    TurnLoop(backend, registryReturning(result), AgentRole.ORCHESTRATOR)
      .run(listOf(Message(Message.Role.USER, "What does the sign say?")))
      .toList()
    return backend
  }

  @Test
  fun aPictureFromAToolReachesAModelThatCanSee() = runTest {
    val backend = loopWith(seesPictures = true, ToolResult.Ok("found", looksAt = listOf(picture)))
    val lastRequest = backend.sent.last()
    val carried = lastRequest.messages.flatMap { it.attachments }
    assertEquals(listOf(picture), carried)
  }

  /** An OpenAI tool message takes a plain string, so a picture has to ride on a user one. */
  @Test
  fun thePictureArrivesOnARoleEveryBackendAcceptsOneOn() = runTest {
    val backend = loopWith(seesPictures = true, ToolResult.Ok("found", looksAt = listOf(picture)))
    val carrying = backend.sent.last().messages.filter { it.attachments.isNotEmpty() }
    assertEquals(Message.Role.USER, carrying.single().role)
  }

  /** The model must not read it as the person having spoken. */
  @Test
  fun thePictureIsLabelledAsComingFromTheTool() = runTest {
    val backend = loopWith(seesPictures = true, ToolResult.Ok("found", looksAt = listOf(picture)))
    val carrying = backend.sent.last().messages.first { it.attachments.isNotEmpty() }
    assertTrue(carrying.text, carrying.text.contains("find_photo"))
    assertTrue(carrying.text, carrying.text.contains("user did not say this"))
  }

  /**
   * The failure with no symptom.
   *
   * Handed nothing and told nothing, a model describes the picture anyway from the words that
   * found it - confidently, and with nothing on screen to suggest it never saw anything.
   */
  @Test
  fun aModelThatCannotSeeIsToldSoRatherThanLeftToGuess() = runTest {
    val backend = loopWith(seesPictures = false, ToolResult.Ok("found", looksAt = listOf(picture)))
    val messages = backend.sent.last().messages
    assertTrue(messages.flatMap { it.attachments }.isEmpty())
    val toolSaid = messages.first { it.role == Message.Role.TOOL }.text
    assertTrue(toolSaid, toolSaid.contains("cannot see"))
    assertTrue(toolSaid, toolSaid.contains("do not describe"))
  }

  @Test
  fun aToolThatOffersNoPictureChangesNothing() = runTest {
    val backend = loopWith(seesPictures = true, ToolResult.Ok("found three, on screen"))
    val messages = backend.sent.last().messages
    assertTrue(messages.flatMap { it.attachments }.isEmpty())
    assertFalse(messages.first { it.role == Message.Role.TOOL }.text.contains("cannot see"))
  }
}
