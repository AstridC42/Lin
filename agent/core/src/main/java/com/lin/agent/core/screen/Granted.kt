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

package com.lin.agent.core.screen

/**
 * Whether an accessibility service of ours is in the phone's list of enabled ones.
 *
 * The list is one string of `package/class` pairs separated by colons, and the class may be
 * written three ways for the same service: in full, as `.Name` relative to the package, or as
 * `Name`. A naive `contains` on the full name therefore reports "not enabled" on a phone where
 * it is - which, for a feature whose whole failure mode is "Lin says it cannot do the thing it
 * can do", is the bug worth a tested function.
 *
 * The same shape as [com.lin.agent.core.voice.pointsAt], which answers the same question about
 * the assistant role, and for the same reason.
 */
fun isEnabledService(enabled: String?, packageName: String, className: String): Boolean {
  val list = enabled.orEmpty()
  if (list.isBlank() || packageName.isBlank() || className.isBlank()) return false
  val short = className.substringAfterLast('.')
  return list.split(':').any { entry ->
    val trimmed = entry.trim()
    if (trimmed.isEmpty()) return@any false
    val pkg = trimmed.substringBefore('/', "")
    if (pkg != packageName) return@any false
    when (val named = trimmed.substringAfter('/', "")) {
      className -> true
      ".$short" -> true
      short -> true
      // The relative form of a class in a sub-package: com.lin.agent/.screen.LinAccessibility.
      else -> named.startsWith(".") && packageName + named == className
    }
  }
}
