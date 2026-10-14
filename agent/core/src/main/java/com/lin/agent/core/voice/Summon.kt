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

package com.lin.agent.core.voice

/**
 * Which summon this launch is.
 *
 * A summon has to happen exactly once, and "once" is harder than it looks on a foldable. The
 * screen that handles it is recreated whenever the device opens or closes, and a recreated
 * screen runs its start-up effects again - so a question caught by the wake word was asked a
 * second time by the act of unfolding the phone. Keying the effect on the words does not help:
 * the words are the same, and it is the composition that is new.
 *
 * So each summon carries a token, and whatever handled it remembers which token that was. The
 * token survives the recreation it has to outlive, and a genuinely new summon brings a new one -
 * which is what makes "say the same thing twice" still work.
 */
object Summon {

  /**
   * The token for this launch, from the three places one can come from, in order.
   *
   * [saved] is what this screen was already working on before it was recreated, and it wins:
   * holding on to it is the entire point. [fromIntent] is a token minted by whatever summoned
   * Lin - the wake word service does this, so that two separate wakes are two separate summons
   * even when the same words were said. [fresh] is for a summon that brought none, which is the
   * ordinary case: the system's assistant intent carries nothing of ours.
   *
   * Blank is treated as absent. An extra that was set to "" is not a token, and falling through
   * to the next source is the only reading that does not then collapse every launch into one.
   */
  fun tokenFor(saved: String?, fromIntent: String?, fresh: String): String =
    saved?.takeIf { it.isNotBlank() }
      ?: fromIntent?.takeIf { it.isNotBlank() }
      ?: fresh

  /**
   * Whether this token has already been acted on.
   *
   * Null means nothing has been handled yet, which is not the same as "handled something
   * blank" - the first summon of a fresh screen has to go through.
   */
  fun alreadyHandled(previous: String?, token: String): Boolean =
    previous != null && previous == token
}
