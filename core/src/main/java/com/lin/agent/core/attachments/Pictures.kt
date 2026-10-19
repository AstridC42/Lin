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

package com.lin.agent.core.attachments

/**
 * The power of two to divide a picture by so its longest side is near [longest].
 *
 * A power of two because that is the only thing `inSampleSize` honours - it rounds down to one
 * anyway, so computing anything else would be pretending to a precision that does not exist.
 * Never below 1, which would be read as "no sampling" by accident rather than on purpose.
 */
fun sampleSizeFor(width: Int, height: Int, longest: Int): Int {
  val biggest = maxOf(width, height)
  if (biggest <= 0 || longest <= 0) return 1
  var sample = 1
  while (biggest / (sample * 2) >= longest) sample *= 2
  return sample
}
