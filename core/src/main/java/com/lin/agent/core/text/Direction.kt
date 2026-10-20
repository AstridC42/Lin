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

package com.lin.agent.core.text

/**
 * Whether [text] reads right to left (Hebrew, Arabic, Persian...), decided the way Unicode's
 * bidi algorithm decides a paragraph: by the first letter with a strong direction. Digits,
 * punctuation, emoji and markdown symbols are skipped, so "**שלום**" and "3 דברים" are right to left
 * and "OK, תודה" is not.
 */
fun isRightToLeft(text: String): Boolean {
  var i = 0
  while (i < text.length) {
    val cp = text.codePointAt(i)
    when (Character.getDirectionality(cp)) {
      Character.DIRECTIONALITY_RIGHT_TO_LEFT,
      Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
      Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
    }
    i += Character.charCount(cp)
  }
  return false
}
