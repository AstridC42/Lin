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

import com.lin.agent.core.calendar.todayLine
import com.lin.agent.core.history.SavedLine
import com.lin.agent.core.persona.personaFrom
import com.lin.agent.core.persona.personaOrDefault
import com.lin.agent.core.persona.personaPrompt
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.tools.disabledFrom
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.LoopEvent
import com.lin.agent.engine.Message
import com.lin.agent.engine.TurnLoop
import com.lin.agent.engine.capabilityPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * One answer from Lin with nobody looking at the phone: for a reply from the car, or a question
 * from the watch. The same tools, persona and date as the app, plus a sentence about where the
 * answer will be read or heard.
 */
class HeadlessTurn(
  private val backends: ChatBackends,
  private val tools: Tools,
  private val settings: SettingsRepository,
) {

  sealed interface Reply {
    data class Answered(val text: String) : Reply

    /** No model to answer with, on the phone or the server. */
    data object NoModel : Reply

    /** The model ran and said nothing usable. */
    data object Empty : Reply
  }

  /** [situation] is a sentence or two of prompt: where the person is and how they will get it. */
  suspend fun answer(lines: List<SavedLine>, situation: String): Reply {
    val backend = (backends.readiness() as? ChatReadiness.Ready)?.backend ?: return Reply.NoModel
    val registry = tools.registry.value
    // No delegation: a server round trip on top of the answer is too long to wait through away
    // from the phone, and the backend chosen is already the best one reachable.
    val withheld = disabledFrom(settings.disabledTools.first()) + DelegateTool.NAME
    val offered = registry.forRole(AgentRole.ORCHESTRATOR).filterNot { it.name in withheld }
    val who = personaPrompt(personaOrDefault(personaFrom(settings.persona.first())))
    val prompt = buildString {
      append(todayLine(System.currentTimeMillis()))
      append(" This is the real date; use it and never a date you remember from elsewhere. ")
      append("You are Lin, an assistant running on this phone. ")
      append(situation)
      if (who.isNotBlank()) append(who).append(' ')
      append(capabilityPrompt(offered))
    }
    val messages =
      lines.takeLast(CONTEXT_LINES).map {
        Message(if (it.fromUser) Message.Role.USER else Message.Role.ASSISTANT, it.text)
      }
    val events =
      TurnLoop(backend = backend, registry = registry, role = AgentRole.ORCHESTRATOR, systemPrompt = prompt, withhold = withheld)
        .run(messages)
        .toList()
    val text = events.filterIsInstance<LoopEvent.Done>().lastOrNull()?.text?.takeIf { it.isNotBlank() }
    return if (text == null) Reply.Empty else Reply.Answered(text)
  }

  private companion object {
    /** How much of the conversation goes back to the model with each question. */
    const val CONTEXT_LINES = 12
  }
}
