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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a conversation inside the window the model actually has.
 *
 * Nothing did this before: `contextWindow` was declared, carried around and never read, so a
 * conversation grew until the engine refused it. That is the failure this project's own notes
 * blame for the fork becoming unworkable, and pictures in the history brought it closer.
 */
class FittingTest {

  private fun user(text: String) = Message(Message.Role.USER, text)

  private fun assistant(text: String) = Message(Message.Role.ASSISTANT, text)

  private fun picture(text: String) =
    Message(Message.Role.USER, text, attachments = listOf(Attachment.Picture("p.jpg", byteArrayOf(1))))

  /** Four characters to a token, so this is a thousand tokens. */
  private fun long(tokens: Int) = "x".repeat(tokens * 4)

  @Test
  fun aShortConversationIsLeftAlone() {
    val conversation = listOf(user("Hello"), assistant("Hi"), user("What time is it?"))
    assertEquals(conversation, fitToContext(conversation, contextTokens = 8192))
  }

  /**
   * The newest message is never dropped - but it is cut if it has to be.
   *
   * It is the question being asked, so a turn without it is a different conversation. Sending
   * it whole when it cannot fit is not keeping it either: that is the engine refusing the turn
   * outright, which is what "Prefill input length exceeds available state entries" was.
   */
  @Test
  fun theQuestionBeingAskedIsCutRatherThanDroppedOrSentWhole() {
    val huge = user(long(10_000))
    val fitted = fitToContext(listOf(user("old"), huge), contextTokens = 2_000, reserve = 100)

    assertEquals(1, fitted.size)
    assertTrue("it was sent whole", fitted.single().text.length < huge.text.length)
    assertTrue("it fits", tokensOf(fitted.single().text) <= 1_900)
  }

  /** Silence would leave somebody reading an answer about a third of their document. */
  @Test
  fun aCutMessageSaysItWasCut() {
    val fitted =
      fitToContext(listOf(user(long(10_000))), contextTokens = 2_000, reserve = 100)
    assertTrue(fitted.single().text, fitted.single().text.contains("Cut off here"))
  }

  /** A picture cannot be cut in half, so it goes before the words asking about it do. */
  @Test
  fun anOversizedMessageLosesItsPictureBeforeItsWords() {
    val asking =
      Message(
        Message.Role.USER,
        long(300),
        attachments = listOf(Attachment.Picture("p.jpg", byteArrayOf(1))),
      )
    val fitted = fitToContext(listOf(asking), contextTokens = 500, reserve = 100)

    assertTrue(fitted.single().attachments.isEmpty())
    // The words survived whole: dropping the picture alone was enough.
    assertEquals(asking.text, fitted.single().text)
  }

  @Test
  fun theOldestGoesFirstWhenItWillNotAllFit() {
    val conversation =
      listOf(user(long(500)), assistant(long(500)), user(long(500)), user("What now?"))
    // 1,100 of budget against three 500-token messages: the oldest cannot fit.
    val fitted = fitToContext(conversation, contextTokens = 1_200, reserve = 100)
    assertEquals("What now?", fitted.last().text)
    assertTrue("kept ${fitted.size}", fitted.size < conversation.size)
    // What survives is the tail, in order.
    assertEquals(conversation.takeLast(fitted.size), fitted)
  }

  // ---- pictures, which are where the tokens are ----

  /**
   * A picture goes before the words around it do.
   *
   * It is worth a few hundred tokens and is almost never what a later question is about, so
   * dropping it buys back the most history per token given up.
   */
  @Test
  fun anOldPictureIsDroppedBeforeTheMessageItWasOn() {
    val conversation = listOf(picture("What is this?"), assistant("A dog."), user("And its breed?"))
    val fitted = fitToContext(conversation, contextTokens = 1_600, reserve = 100)

    assertEquals(3, fitted.size)
    assertTrue("the words survived", fitted.first().text == "What is this?")
    assertTrue("the picture went", fitted.first().attachments.isEmpty())
  }

  /** The picture on the question being asked is the one that must survive. */
  @Test
  fun thePictureOnTheNewestMessageIsKept() {
    val asking = picture("What does this say?")
    val fitted = fitToContext(listOf(user("old"), asking), contextTokens = 2_000, reserve = 100)
    assertEquals(1, fitted.last().attachments.size)
  }

  @Test
  fun severalOldPicturesAllGo() {
    val conversation = listOf(picture("one"), picture("two"), picture("three"), user("and now?"))
    val fitted = fitToContext(conversation, contextTokens = 2_000, reserve = 100)
    assertEquals(0, fitted.dropLast(1).sumOf { it.attachments.size })
  }

  // ---- not leaving nonsense at the front ----

  /**
   * A tool result with no visible question before it reads as something the user said.
   *
   * Which is worse than the gap dropping it leaves.
   */
  @Test
  fun aToolResultIsNotLeftStrandedAtTheStart() {
    val conversation =
      listOf(
        user(long(2_000)),
        Message(Message.Role.TOOL, "It is 4pm.", toolName = "clock"),
        assistant("Four."),
        user("Thanks"),
      )
    val fitted = fitToContext(conversation, contextTokens = 1_200, reserve = 100)
    assertTrue(fitted.first().role != Message.Role.TOOL)
  }

  @Test
  fun aConversationThatIsAllToolResultsStillSendsSomething() {
    val conversation =
      listOf(
        Message(Message.Role.TOOL, "a", toolName = "x"),
        Message(Message.Role.TOOL, "b", toolName = "x"),
      )
    assertEquals(1, fitToContext(conversation, contextTokens = 200, reserve = 10).size)
  }

  // ---- when the window is unknown ----

  /**
   * Zero means the backend did not say, which a server does not.
   *
   * Guessing a window here would trim conversations that were fine on a machine with far more
   * room than a phone.
   */
  @Test
  fun anUnknownWindowTrimsNothing() {
    val conversation = (1..50).map { user(long(500)) }
    assertEquals(conversation, fitToContext(conversation, contextTokens = 0))
  }

  @Test
  fun anEmptyConversationIsReturnedAsItIs() {
    val empty = emptyList<Message>()
    assertSame(empty, fitToContext(empty, contextTokens = 8192))
  }

  // ---- the estimate itself ----

  @Test
  fun tokensAreCountedRoughlyFourCharactersEach() {
    assertEquals(0, tokensOf(""))
    assertEquals(1, tokensOf("abc"))
    assertEquals(1, tokensOf("abcd"))
    assertEquals(2, tokensOf("abcde"))
    assertEquals(250, tokensOf("x".repeat(1000)))
  }
}
