/*
 * Copyright 2026 Google LLC
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

import android.util.Log
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.skills.getSkillContent
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGLoadSkillTool"

class LoadSkillTool(private val skillsProvider: SkillsProvider) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  /**
   * Loads skill.
   *
   * The description matters more than it looks. It is the only thing a model sees about this tool
   * at the point of choosing one, and "Loads a skill" gives it no reason to prefer this over
   * answering from memory - which is exactly what happened: with the Wikipedia skill installed and
   * listed, a request to look something up was answered from the model's own training instead.
   * Naming the kinds of request skills cover, and saying to load before answering, is what puts
   * this tool in the running at all.
   */
  @Tool(
    description =
      "Load the instructions for one of the skills listed in your system instructions. Call this " +
        "BEFORE answering anything a skill covers - looking a fact up, Wikipedia, calendars, " +
        "email, QR codes, maps, reminders by notification. Pass the skill's exact name from that " +
        "list."
  )
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val skill = skillsProvider.loadSkill(skillName)
      val skillContent = skill?.getSkillContent() ?: "Skill not found"
      Log.d(TAG, "load skill. Skill content:\n$skillContent")
      if (skill != null) {
        executionContext
          ?.actionChannel
          ?.send(
            SkillProgressToolAction(
              label = "Loading skill \"$skillName\"",
              inProgress = true,
              addItemTitle = "Load \"${skill.name}\"",
              addItemDescription = "Description: ${skill.description}",
              customData = skill,
            )
          )
      } else {
        executionContext
          ?.actionChannel
          ?.send(
            SkillProgressToolAction(
              label = "Failed to load skill \"$skillName\"",
              inProgress = false,
            )
          )
      }

      mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
    }
  }
}
