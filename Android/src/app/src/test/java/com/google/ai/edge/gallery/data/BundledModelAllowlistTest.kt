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

package com.google.ai.edge.gallery.data

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the allowlist shipped in `assets/`.
 *
 * This file is the last-resort source of models: without it, an install whose remote fetch fails
 * has nothing to offer and the assistant cannot start. A parse failure here would only show up on
 * a real device, on a fresh install, with no network — so it is checked at build time instead.
 */
class BundledModelAllowlistTest {

  private val bundled = File("src/main/assets/model_allowlist.json")

  private fun parse(): ModelAllowlist =
    Gson().fromJson(bundled.readText(), ModelAllowlist::class.java)

  @Test
  fun bundledAllowlistExists() {
    assertTrue(
      "assets/model_allowlist.json is missing; a fresh install would have no models",
      bundled.exists(),
    )
  }

  @Test
  fun bundledAllowlistParsesIntoTheSameTypeTheAppUses() {
    val allowlist = parse()
    assertNotNull(allowlist)
    assertTrue("the bundled allowlist has no models", allowlist.models.isNotEmpty())
  }

  @Test
  fun bundledAllowlistOffersModelsTheAssistantCanUse() {
    val assistantModels =
      parse().models.filter { model ->
        model.disabled != true &&
          (model.taskTypes.contains(BuiltInTaskId.LLM_CHAT) ||
            model.taskTypes.contains(BuiltInTaskId.LLM_AGENT_CHAT))
      }
    assertTrue(
      "no general-purpose chat model in the bundled allowlist, so the assistant would start " +
        "with an empty model picker",
      assistantModels.isNotEmpty(),
    )
  }

  @Test
  fun everyModelHasWhatTheDownloaderNeeds() {
    for (model in parse().models.filter { it.disabled != true }) {
      assertTrue("a model has no name", model.name.isNotBlank())
      assertTrue("${model.name} has no taskTypes", model.taskTypes.isNotEmpty())
      // AICore models are resolved through Play Services rather than downloaded.
      if (model.runtimeType != RuntimeType.AICORE) {
        assertTrue("${model.name} has no model file", model.modelFile.isNotBlank())
        assertTrue("${model.name} has no size", model.sizeInBytes > 0)
      }
    }
  }

  @Test
  fun chatCapabilitiesAreMirroredOntoTheAssistantTask() {
    val withChatCapability =
      parse().models.firstOrNull { allowed ->
        allowed.capabilityToTaskTypes?.values?.any { it.contains(BuiltInTaskId.LLM_CHAT) } == true
      }
    assertNotNull(
      "expected at least one model advertising a capability for llm_chat",
      withChatCapability,
    )

    // Whatever the model can do in plain chat it must also be allowed to do in the assistant, or
    // options like thinking would be hidden on the app's main screen.
    for ((capability, taskTypes) in withChatCapability!!.toModel().capabilityToTaskTypes) {
      if (taskTypes.contains(BuiltInTaskId.LLM_CHAT)) {
        assertTrue(
          "$capability is offered for llm_chat but not for the assistant",
          taskTypes.contains(BuiltInTaskId.ASSISTANT),
        )
      }
    }
  }
}
