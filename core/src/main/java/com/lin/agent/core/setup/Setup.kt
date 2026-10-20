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

package com.lin.agent.core.setup

/**
 * The first-run setup's steps, in order. [essential] ones are what Lin cannot work without;
 * the rest each switch on a feature and can be skipped.
 */
enum class SetupStep(val essential: Boolean) {
  WELCOME(false),
  BRAIN(true),
  NOTIFICATIONS(true),
  VOICE(false),
  ASSISTANT(false),
  PHONE(false),
  MESSAGES(false),
  SOUND(false),
  PHOTOS(false),
  SCREEN(false),
  ALARMS(false),
  BATTERY(false),
  NEARBY(false),
  TOKEN(false),
  DONE(false),
}

/** The steps that have something to check, which is all but the first and last pages. */
val checkedSteps: List<SetupStep> = SetupStep.entries.filter { it != SetupStep.WELCOME && it != SetupStep.DONE }

/** How many of the checked steps are done, out of how many. */
fun progress(done: Set<SetupStep>): Pair<Int, Int> = checkedSteps.count { it in done } to checkedSteps.size

/** Essential steps still missing; Lin can be used once this is empty. */
fun missingEssentials(done: Set<SetupStep>): List<SetupStep> = checkedSteps.filter { it.essential && it !in done }

/**
 * Where "Next" goes from [current]: the next step that is not already done, so somebody whose
 * phone already allows most of this is not walked through screens with nothing to do. Ends on
 * [SetupStep.DONE].
 */
fun nextStep(current: SetupStep, done: Set<SetupStep>): SetupStep =
  SetupStep.entries.drop(current.ordinal + 1).firstOrNull { it == SetupStep.DONE || it !in done } ?: SetupStep.DONE
