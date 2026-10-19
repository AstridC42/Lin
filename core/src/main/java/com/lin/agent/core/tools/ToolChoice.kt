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

package com.lin.agent.core.tools

/**
 * Which tools the user has switched off, stored as one string.
 *
 * Stored as the **off** list rather than the on list, and that is the decision this file
 * exists to hold. A stored on-list goes wrong the first time a new tool ships: it is not in
 * anybody's saved list, so it arrives switched off for every existing user, invisibly, and the
 * feature appears not to work. Storing what was turned off means a new tool is on by default
 * and only what somebody actually refused stays refused.
 *
 * One string rather than a set, because the settings store keeps strings and a list of names
 * is not worth a second storage format. Newline-separated: a tool name may contain dots,
 * hyphens and underscores by the MCP naming rules, and a comma is the separator most likely
 * to turn up inside one.
 */
fun disabledFrom(stored: String?): Set<String> =
  stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * The off-list as it is stored.
 *
 * Sorted, so that turning a tool off and on again leaves the stored string as it was rather
 * than reordered - which matters only because it makes a settings backup diffable, and that
 * has been worth having every time something went wrong.
 */
fun storedFrom(disabled: Set<String>): String =
  disabled.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("\n")

/** Whether the model may be offered this tool. */
fun isEnabled(name: String, disabled: Set<String>): Boolean = name !in disabled

/**
 * The off-list with one tool switched.
 *
 * Returned rather than mutated, because the caller writes it straight back to storage and a
 * set that was edited in place would already disagree with what is stored if the write failed.
 */
fun withTool(disabled: Set<String>, name: String, enabled: Boolean): Set<String> =
  if (enabled) disabled - name else disabled + name
