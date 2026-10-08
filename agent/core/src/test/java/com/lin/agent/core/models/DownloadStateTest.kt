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

package com.lin.agent.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of several work records for one model the settings screen should believe.
 *
 * There is more than one because starting a download replaces any earlier attempt rather than
 * quietly declining to, and a replaced attempt stays in WorkManager's database as a cancelled
 * record. Nothing promises what order those come back in, so the choice has to be made on content.
 */
class DownloadStateTest {

  private fun running(received: Long) =
    DownloadState.Running(percent = 0, receivedBytes = received, totalBytes = 1_000)

  @Test
  fun nothingAtAllIsIdle() {
    assertEquals(DownloadState.Idle, mostRelevant(emptyList()))
  }

  /**
   * The case that made this a function rather than `lastOrNull()`.
   *
   * Replacing a download leaves the cancelled record beside the live one. Reading the wrong one
   * shows Download on a model that is downloading - and pressing it restarts the transfer.
   */
  @Test
  fun aCancelledRecordNeverHidesALiveOne() {
    val states = listOf(running(2_048), DownloadState.Idle)
    assertEquals(running(2_048), mostRelevant(states))
    assertEquals("order must not matter", running(2_048), mostRelevant(states.reversed()))
  }

  @Test
  fun transferringBeatsWaitingToStart() {
    assertEquals(running(99), mostRelevant(listOf(DownloadState.Waiting, running(99))))
  }

  /** Two live records should report the one that has got further, not whichever came first. */
  @Test
  fun theFurthestAlongTransferWins() {
    assertEquals(
      running(5_000),
      mostRelevant(listOf(running(12), running(5_000), running(300))),
    )
  }

  /** A retry that is queued is news; the attempt that failed before it is not. */
  @Test
  fun aQueuedRetryBeatsTheFailureBeforeIt() {
    assertEquals(
      DownloadState.Waiting,
      mostRelevant(listOf(DownloadState.Failed("no network"), DownloadState.Waiting)),
    )
  }

  /** The file is on the phone. An earlier failed attempt is history. */
  @Test
  fun succeedingBeatsAnEarlierFailure() {
    assertEquals(
      DownloadState.Done,
      mostRelevant(listOf(DownloadState.Failed("truncated"), DownloadState.Done)),
    )
  }

  /** With nothing in flight, the failure is the thing the user needs to see. */
  @Test
  fun aFailureIsReportedWhenItIsAllThereIs() {
    assertEquals(
      DownloadState.Failed("the server refused it"),
      mostRelevant(listOf(DownloadState.Idle, DownloadState.Failed("the server refused it"))),
    )
  }

  /** The first failure, not the last: it is the one whose message explains the rest. */
  @Test
  fun theFirstFailureIsTheOneReported() {
    assertEquals(
      DownloadState.Failed("first"),
      mostRelevant(listOf(DownloadState.Failed("first"), DownloadState.Failed("second"))),
    )
  }

  /** Only cancelled records: nothing is happening and nothing went wrong. */
  @Test
  fun onlyCancelledRecordsReadAsIdle() {
    assertEquals(
      DownloadState.Idle,
      mostRelevant(listOf(DownloadState.Idle, DownloadState.Idle)),
    )
  }
}
