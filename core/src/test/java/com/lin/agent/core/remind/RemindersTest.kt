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

package com.lin.agent.core.remind

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {

  // A Friday, in the zone the first person to use Lin is in.
  private val zone = ZoneId.of("Asia/Jerusalem")
  private val now = at(2026, 10, 16, 14, 30)

  private fun at(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    zone: ZoneId = this.zone,
  ): Long = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

  private fun fires(spoken: String, from: Long = now): Long? = whenItFires(spoken, from, zone)

  @Test
  fun `in so many minutes is counted from now`() {
    assertEquals(now + 25 * 60_000L, fires("in 25 minutes"))
    assertEquals(now + 25 * 60_000L, fires("in 25 min"))
    assertEquals(now + 2 * 3_600_000L, fires("in 2 hours"))
    assertEquals(now + 3 * 86_400_000L, fires("in 3 days"))
  }

  @Test
  fun `the phrases people actually say for a round amount of time`() {
    assertEquals(now + 1_800_000L, fires("in half an hour"))
    assertEquals(now + 3_600_000L, fires("in an hour"))
    assertEquals(now + 60_000L, fires("in a minute"))
  }

  @Test
  fun `a clock time today means today when it is still ahead`() {
    assertEquals(at(2026, 10, 16, 18, 30), fires("18:30"))
    assertEquals(at(2026, 10, 16, 18, 30), fires("at 6:30 pm"))
  }

  @Test
  fun `a clock time that has gone means the next one`() {
    // Nobody sets a reminder for a moment this morning. Said with no day, 09:00 at half past
    // two in the afternoon is tomorrow morning.
    assertEquals(at(2026, 10, 17, 9, 0), fires("09:00"))
    assertEquals(at(2026, 10, 17, 7, 0), fires("7am"))
  }

  @Test
  fun `but a time somebody said was today is left in the past`() {
    // So the caller can say "that was this morning" rather than silently setting one for
    // tomorrow, which is the kind of help nobody asked for.
    val said = fires("today at 09:00")
    assertEquals(at(2026, 10, 16, 9, 0), said)
    assertTrue("$said", said!! < now)
  }

  @Test
  fun `morning noon and night are the same hour every time`() {
    assertEquals(at(2026, 10, 17, 9, 0), fires("tomorrow morning"))
    assertEquals(at(2026, 10, 16, 21, 0), fires("tonight"))
    assertEquals(at(2026, 10, 16, 19, 0), fires("this evening"))
    assertEquals(at(2026, 10, 17, 12, 0), fires("tomorrow at noon"))
    assertEquals(at(2026, 10, 17, 0, 0), fires("tomorrow midnight"))
  }

  @Test
  fun `midnight is not read as night`() {
    // "night" is in "midnight", and nine in the evening is not midnight.
    assertEquals(at(2026, 10, 17, 0, 0), fires("tomorrow at midnight"))
  }

  @Test
  fun `am and pm move the hour and bare hours do not`() {
    assertEquals(at(2026, 10, 17, 0, 15), fires("tomorrow 12:15 am"))
    assertEquals(at(2026, 10, 16, 23, 45), fires("23:45"))
    assertEquals(at(2026, 10, 17, 12, 15), fires("tomorrow 12:15 pm"))
  }

  @Test
  fun `a weekday means the next one, and a week on when it is today and gone`() {
    // Now is a Friday afternoon.
    assertEquals(at(2026, 10, 19, 9, 0), fires("monday at 9"))
    assertEquals(at(2026, 10, 16, 18, 0), fires("friday at 18:00"))
    assertEquals(at(2026, 10, 23, 9, 0), fires("friday at 9am"))
  }

  @Test
  fun `a weekday is matched as a word and not inside one`() {
    // "sat" is in "saturate" and "mon" is in "money". A reminder that quietly lands next
    // Saturday is a bug nobody reports; they just stop setting them.
    assertEquals(at(2026, 10, 16, 18, 0), fires("at 18:00 to check the money"))
  }

  @Test
  fun `a written date is read as the date and not as an hour`() {
    // "2026-10-17 07:00" has three pairs of digits that could pass for an hour, and two of
    // them are the date.
    assertEquals(at(2026, 10, 17, 7, 0), fires("2026-10-17 07:00"))
    assertEquals(at(2026, 10, 17, 7, 0), fires("2026-10-17T07:00"))
  }

  @Test
  fun `a written date that has passed is returned, not moved`() {
    val said = fires("2026-10-15 07:00")
    assertEquals(at(2026, 10, 15, 7, 0), said)
  }

  @Test
  fun `a date nobody could have meant is nothing rather than a guess`() {
    assertNull(fires("2026-13-45 07:00"))
  }

  @Test
  fun `a phrase that names no moment is nothing`() {
    // The contract the whole tool rests on. A model asked for a time will happily say "soon",
    // and a reminder at a time nobody chose is worse than no reminder at all.
    for (said in listOf("", "   ", "soon", "later", "when I get home", "25 minutes", "99:99")) {
      assertNull(said, fires(said))
    }
  }

  @Test
  fun `an hour that is not an hour is nothing`() {
    assertNull(fires("tomorrow at 25:00"))
    assertNull(fires("tomorrow at 10:75"))
  }

  @Test
  fun `the zone is the one passed in, not the machine's`() {
    val tokyo = ZoneId.of("Asia/Tokyo")
    val said = whenItFires("tomorrow at 07:00", now, tokyo)
    assertEquals(at(2026, 10, 17, 7, 0, tokyo), said)
  }

  // ---- reading it back ----

  @Test
  fun `the moment is read back in words somebody can check`() {
    assertEquals("today at 18:30", whenInWords(at(2026, 10, 16, 18, 30), now, zone))
    assertEquals("tomorrow at 07:00", whenInWords(at(2026, 10, 17, 7, 0), now, zone))
    assertEquals("Monday at 09:00", whenInWords(at(2026, 10, 19, 9, 0), now, zone))
    assertEquals("26 Oct at 09:00", whenInWords(at(2026, 10, 26, 9, 0), now, zone))
  }

  @Test
  fun `a time read back always has two digits`() {
    // "7:5" is not a time, and this string is the last chance anybody has to notice the
    // reminder is set for the wrong moment.
    assertEquals("today at 07:05", whenInWords(at(2026, 10, 16, 7, 5), now, zone))
  }

  // ---- the list ----

  @Test
  fun `reminders are kept soonest first`() {
    val later = Reminder("b", "later", at(2026, 10, 18, 9, 0))
    val sooner = Reminder("a", "sooner", at(2026, 10, 16, 18, 0))
    assertEquals(listOf(sooner, later), withReminder(listOf(later), sooner))
  }

  @Test
  fun `setting the same reminder twice leaves one`() {
    val moment = at(2026, 10, 16, 18, 0)
    val id = reminderId(moment, "Take the bins out")
    assertEquals(id, reminderId(moment, "take the bins  out "))
    val list = withReminder(emptyList(), Reminder(id, "Take the bins out", moment))
    assertEquals(1, withReminder(list, Reminder(id, "Take the bins out", moment)).size)
  }

  @Test
  fun `what is due and what is still ahead`() {
    val gone = Reminder("a", "gone", now - 1)
    val ahead = Reminder("b", "ahead", now + 1)
    val exactly = Reminder("c", "now", now)
    val all = listOf(gone, ahead, exactly)
    // On the minute counts as due: an alarm that fires a millisecond early must still deliver.
    assertEquals(listOf(gone, exactly), dueReminders(all, now))
    assertEquals(listOf(ahead), pendingReminders(all, now))
  }

  @Test
  fun `one is removed and the rest are kept`() {
    val list = listOf(Reminder("a", "one", now), Reminder("b", "two", now))
    assertEquals(listOf(Reminder("b", "two", now)), withoutReminder(list, "a"))
    assertEquals(list, withoutReminder(list, "nothing like this"))
  }

  @Test
  fun `cancelling by what it was about finds the one it was about`() {
    val dentist = Reminder("a", "Dentist appointment", now + 1)
    val bins = Reminder("b", "Take the bins out", now + 2)
    val list = listOf(dentist, bins)
    assertEquals(dentist, reminderMatching(list, "the dentist"))
    assertEquals(bins, reminderMatching(list, "Take the bins out"))
    assertEquals(dentist, reminderMatching(list, "a"))
  }

  @Test
  fun `an ambiguous phrase cancels nothing`() {
    // Cancelling the wrong one fails silently: nothing happens at the moment something should
    // have. Better to come back with nothing and let the caller ask which.
    val list =
      listOf(Reminder("a", "call mum", now + 1), Reminder("b", "call the dentist", now + 2))
    assertNull(reminderMatching(list, "call"))
    assertNull(reminderMatching(list, "the shopping"))
    assertNull(reminderMatching(list, ""))
    assertNotNull(reminderMatching(list, "call the dentist"))
  }

  // ---- storage ----

  @Test
  fun `reminders survive a round trip`() {
    val list =
      listOf(
        Reminder("a", "Take the bins out", at(2026, 10, 16, 18, 0)),
        Reminder("b", "Dentist", at(2026, 10, 17, 9, 0)),
      )
    assertEquals(list, remindersFrom(storedReminders(list)))
  }

  @Test
  fun `nothing stored is no reminders`() {
    assertEquals(emptyList<Reminder>(), remindersFrom(null))
    assertEquals(emptyList<Reminder>(), remindersFrom(""))
    assertEquals(emptyList<Reminder>(), remindersFrom("{not json"))
  }

  @Test
  fun `one damaged row does not take the others with it`() {
    // The failure would be invisible until the morning nothing goes off.
    val stored =
      """[{"id":"a","text":"Dentist","at":${at(2026, 10, 17, 9, 0)}},{"text":""},{"at":0}]"""
    val read = remindersFrom(stored)
    assertEquals(1, read.size)
    assertEquals("Dentist", read.first().text)
  }

  @Test
  fun `a row with no id gets one rather than being dropped`() {
    val moment = at(2026, 10, 17, 9, 0)
    val read = remindersFrom("""[{"text":"Dentist","at":$moment}]""")
    assertEquals(listOf(Reminder(reminderId(moment, "Dentist"), "Dentist", moment)), read)
  }

  @Test
  fun `stored reminders come back soonest first however they went in`() {
    val sooner = at(2026, 10, 16, 18, 0)
    val later = at(2026, 10, 18, 9, 0)
    val read = remindersFrom("""[{"id":"b","text":"later","at":$later},{"id":"a","text":"sooner","at":$sooner}]""")
    assertEquals(listOf("a", "b"), read.map { it.id })
  }
}
