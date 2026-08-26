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

package com.google.ai.edge.gallery.skills

import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.proto.Skill

const val SKILL_INSTRUCTIONS_TEMPLATE = "---\nname: %s\ndescription: %s\n---\n\n%s"

fun Skill.getSkillContent(): String {
  return SKILL_INSTRUCTIONS_TEMPLATE.format(name, description, instructions)
}

/**
 * Formats the enabled skills for the `___SKILLS___` placeholder.
 *
 * Each skill is written as the call that loads it rather than as a name and a description under
 * headings. Two things come out of that. The list halves in size, which matters on a phone model
 * whose whole budget is a few thousand tokens and is shared with the tool schemas. And the exact
 * call the model has to make is on the same line as the reason to make it, instead of being
 * described three paragraphs earlier - which is the difference between a small model reaching for
 * a skill and answering the question from memory.
 */
fun formatSelectedSkills(skills: List<Skill>): String {
  return skills
    .filter { it.selected }
    .joinToString("\n") { skill ->
      "- load_skill(\"${skill.name}\") - ${summarize(skill.description)}"
    }
}

/** The first line of [description], short enough that one skill stays one line. */
private fun summarize(description: String): String {
  val firstLine = description.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
  return if (firstLine.length <= MAX_SKILL_SUMMARY_CHARS) firstLine
  else firstLine.take(MAX_SKILL_SUMMARY_CHARS).substringBeforeLast(' ') + "..."
}

/** Long enough for a real description, short enough that twelve of them are not the whole prompt. */
private const val MAX_SKILL_SUMMARY_CHARS = 120

fun Skill.getJsSkillUrl(scriptName: String): String? {
  var baseUrl = ""
  // Construct a local URL for imported skill and built-in skills.
  if (importDirName.isNotEmpty()) {
    baseUrl = "$LOCAL_URL_BASE/$importDirName"
  }
  // Use skill.skillUrl if set.
  else if (skillUrl.isNotEmpty()) {
    baseUrl = skillUrl
  }
  if (baseUrl.isEmpty()) return null
  return "$baseUrl/scripts/$scriptName"
}

fun Skill.getJsSkillWebviewUrl(url: String): String {
  if (url.startsWith("http")) return url
  var baseUrl = ""
  // Construct a local URL for imported skill.
  if (importDirName.isNotEmpty()) {
    baseUrl = "$LOCAL_URL_BASE/$importDirName"
  }
  // Use skill.skillUrl if set.
  else if (skillUrl.isNotEmpty()) {
    baseUrl = skillUrl
  }
  if (baseUrl.isEmpty()) return url
  return "$baseUrl/assets/$url"
}
