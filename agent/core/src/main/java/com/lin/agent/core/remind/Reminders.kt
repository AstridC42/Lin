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

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing to be told at one moment.
 *
 * An instant rather than the words the person said, because the words stop meaning the same
 * thing almost immediately: "in twenty minutes" written down and read back after a reboot is
 * twenty minutes from the reboot. The phrase is turned into a moment once, here, and the
 * moment is what is stored and what fires.
 */
data class Reminder(val id: String, val text: String, val atMillis: Long)

/**
 * The moment a phrase means, or null when it does not name one.
 *
 * Null rather than a guess, and that is the whole contract. A model asked to produce a time
 * will happily produce "soon", and a reminder set for a time nobody chose is worse than no
 * reminder: it fires at the wrong moment and the person stops trusting the ones that are
 * right. Null makes the tool say "tell me when", which is a question with an answer.
 *
 * Relative phrases are resolved against [nowMillis] and bare clock times against [zone], both
 * passed in rather than read from the clock, because a function that reads the clock itself
 * can only be tested at the time of day the test happens to run.
 *
 * What rolls forward and what does not is deliberate. "19:00" with no day means the next 19:00
 * - a reminder for a time that has already passed today is obviously meant for tomorrow. But
 * "today at 19:00" said at nine in the evening means a moment that is gone, and this returns
 * it unchanged so the caller can say so, because quietly moving it to tomorrow would be this
 * function deciding what somebody meant.
 */
fun whenItFires(spoken: String, nowMillis: Long, zone: ZoneId): Long? {
  val text = spoken.lowercase(Locale.ROOT).replace(' ', ' ').trim().replace(WHITESPACE, " ")
  if (text.isEmpty()) return null

  afterNow(text)?.let { return nowMillis + it }

  val nowHere = Instant.ofEpochMilli(nowMillis).atZone(zone)
  val written = ISO_DATE.find(text)
  val named =
    if (written == null) wordDateIn(text, nowHere.toLocalDate())
    else
      // A date nobody could have meant - "2026-13-45" - is not a date to work around. Null
      // makes the tool ask again, which is the only honest answer available.
      Day(runCatching { LocalDate.parse(written.value) }.getOrNull() ?: return null, rollDays = 0)

  // The written date comes out before the clock is looked for. "2026-10-17 07:00" holds three
  // pairs of digits that could pass for an hour and two of them are the date, which is how a
  // seven in the morning becomes five in the afternoon.
  val rest = if (written == null) text else text.removeRange(written.range)
  val time = clockIn(rest) ?: wordTimeIn(rest) ?: return null
  val day = named ?: Day(nowHere.toLocalDate(), rollDays = 1)

  var at = ZonedDateTime.of(day.date, time, zone)
  if (day.rollDays > 0 && at.toInstant().toEpochMilli() <= nowMillis) {
    at = at.plusDays(day.rollDays.toLong())
  }
  return at.toInstant().toEpochMilli()
}

/** How long "in ten minutes" is, in milliseconds, or null if that is not what was said. */
private fun afterNow(text: String): Long? {
  SPELLED.forEach { (phrase, millis) -> if (text == phrase) return millis }
  val found = RELATIVE.matchEntire(text) ?: return null
  val count = found.groupValues[1].toLongOrNull() ?: return null
  val unit =
    when (found.groupValues[2].trimEnd('s')) {
      "second", "sec" -> 1_000L
      "minute", "min" -> 60_000L
      "hour", "hr", "h" -> 3_600_000L
      "day" -> 86_400_000L
      "week" -> 604_800_000L
      else -> return null
    }
  return count * unit
}

/** The date a phrase names, and how far to jump when that date's time has gone. */
private class Day(val date: LocalDate, val rollDays: Int)

private fun wordDateIn(text: String, today: LocalDate): Day? {
  if (word(text, "tomorrow")) return Day(today.plusDays(1), rollDays = 0)
  if (word(text, "today") || word(text, "tonight")) return Day(today, rollDays = 0)
  for (day in DayOfWeek.entries) {
    val name = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ROOT)
    if (word(text, name) || word(text, name.take(3))) {
      // Forward to that weekday, and a week on if it is today and the time has gone. "Monday"
      // said on a Monday evening is next Monday, not an hour ago.
      val ahead = (day.value - today.dayOfWeek.value + 7) % 7
      return Day(today.plusDays(ahead.toLong()), rollDays = 7)
    }
  }
  return null
}

/**
 * Whole words only.
 *
 * "sat" is in "saturate" and "mon" is in "money", and a reminder that lands next Monday
 * because somebody said "money" is the kind of bug nobody reports - they just stop using it.
 */
private fun word(text: String, needle: String): Boolean =
  Regex("(^|[^a-z])" + Regex.escape(needle) + "([^a-z]|$)").containsMatchIn(text)

private fun clockIn(text: String): LocalTime? {
  val found = CLOCK.find(text) ?: return null
  var hour = found.groupValues[1].toIntOrNull() ?: return null
  val minute = found.groupValues[2].toIntOrNull() ?: 0
  val half = found.groupValues[3]
  if (minute > 59) return null
  when {
    half == "pm" && hour < 12 -> hour += 12
    half == "am" && hour == 12 -> hour = 0
    // No am or pm, so it is a 24-hour clock, and 25 o'clock is not a time.
    half.isEmpty() && hour > 23 -> return null
    hour > 23 -> return null
  }
  return LocalTime.of(hour, minute)
}

/**
 * The times of day people actually say.
 *
 * These are conventions, not facts, and that is why they are here rather than guessed at by a
 * model: "evening" means the same hour every time Lin is asked, so a reminder set for "this
 * evening" and one set for "tonight" are not ten minutes apart for no visible reason.
 */
private fun wordTimeIn(text: String): LocalTime? {
  for ((name, time) in WORD_TIMES) if (word(text, name)) return time
  return null
}

private val WORD_TIMES =
  listOf(
    "midnight" to LocalTime.of(0, 0),
    "midday" to LocalTime.of(12, 0),
    "noon" to LocalTime.of(12, 0),
    "morning" to LocalTime.of(9, 0),
    "afternoon" to LocalTime.of(15, 0),
    "evening" to LocalTime.of(19, 0),
    "tonight" to LocalTime.of(21, 0),
    "night" to LocalTime.of(21, 0),
  )

private val WHITESPACE = Regex("\\s+")
private val RELATIVE =
  Regex("^in (\\d{1,4}) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?|h|days?|weeks?)$")
private val SPELLED =
  listOf(
    "in a minute" to 60_000L,
    "in a few minutes" to 300_000L,
    "in half an hour" to 1_800_000L,
    "in an hour" to 3_600_000L,
    "in a day" to 86_400_000L,
    "in a week" to 604_800_000L,
  )
/** Words somebody says around the thing they mean rather than as part of it. */
private val NOTHING_WORDS =
  setOf("the", "one", "that", "this", "about", "for", "and", "reminder", "reminders", "remind", "cancel", "delete")

private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
private val CLOCK = Regex("(?<![\\d:])(\\d{1,2})(?::(\\d{2}))? ?(am|pm)?(?![\\d:])")

/**
 * The moment, in the words Lin reads back.
 *
 * Read back every single time, because this is the one part of a reminder that can be wrong in
 * a way nobody notices until it is too late to matter. "Set for tomorrow at 07:00" gives the
 * person the one chance they will get to say "no, today".
 */
fun whenInWords(atMillis: Long, nowMillis: Long, zone: ZoneId): String {
  val at = Instant.ofEpochMilli(atMillis).atZone(zone)
  val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
  val clock = String.format(Locale.ROOT, "%02d:%02d", at.hour, at.minute)
  val days = at.toLocalDate().toEpochDay() - today.toEpochDay()
  return when {
    days == 0L -> "today at $clock"
    days == 1L -> "tomorrow at $clock"
    days in 2..6 ->
      at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " at $clock"
    else ->
      "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} at $clock"
  }
}

/**
 * A reminder's id: the moment and the words, so setting the same thing twice is one reminder.
 *
 * Spacing and case are flattened first. A model asked twice produces "Take the bins out" and
 * "take the bins  out", and two notifications a second apart for the same thing is the sort of
 * thing that gets an app's notifications turned off.
 */
fun reminderId(atMillis: Long, text: String): String =
  "r" +
    atMillis.toString(36) +
    "-" +
    text.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT).hashCode().toString(36)

/** Soonest first, one per id, because the list is read aloud as often as it is written. */
fun withReminder(reminders: List<Reminder>, added: Reminder): List<Reminder> =
  (reminders.filterNot { it.id == added.id } + added).sortedBy { it.atMillis }

fun withoutReminder(reminders: List<Reminder>, id: String): List<Reminder> =
  reminders.filterNot { it.id == id }

/** The ones whose moment has arrived. */
fun dueReminders(reminders: List<Reminder>, nowMillis: Long): List<Reminder> =
  reminders.filter { it.atMillis <= nowMillis }

/** The ones still ahead, which are the only ones worth arming an alarm for or reading out. */
fun pendingReminders(reminders: List<Reminder>, nowMillis: Long): List<Reminder> =
  reminders.filter { it.atMillis > nowMillis }

/**
 * The reminder somebody means when they say "cancel the one about the dentist".
 *
 * Whole words, and only when exactly one matches. Cancelling the wrong reminder is a silent
 * failure - nothing happens at the moment it should have - so an ambiguous phrase has to come
 * back as nothing and let the caller ask which one.
 */
fun reminderMatching(reminders: List<Reminder>, said: String): Reminder? {
  val wanted = said.lowercase(Locale.ROOT).trim()
  if (wanted.isEmpty()) return null
  reminders.firstOrNull { it.id == said }?.let { return it }
  val exact = reminders.filter { it.text.lowercase(Locale.ROOT).trim() == wanted }
  if (exact.size == 1) return exact.first()
  // Words that carry nothing go first. "the dentist" has to find "Dentist appointment", and
  // it cannot if every reminder is required to contain the word "the".
  val words = wanted.split(WHITESPACE).filter { it.length > 2 && it !in NOTHING_WORDS }
  if (words.isEmpty()) return null
  val some =
    reminders.filter { reminder ->
      val text = reminder.text.lowercase(Locale.ROOT)
      words.all { word(text, it) }
    }
  return some.singleOrNull()
}

/**
 * The reminders as stored, which is one JSON array in the settings.
 *
 * Damage reads as fewer reminders rather than as a crash, one entry at a time: a single
 * unreadable row must not take the rest of somebody's reminders with it, because the failure
 * would be invisible until the morning nothing went off.
 */
fun remindersFrom(stored: String?): List<Reminder> {
  val text = stored.orEmpty().trim()
  if (text.isEmpty()) return emptyList()
  val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
  val out = mutableListOf<Reminder>()
  for (i in 0 until array.length()) {
    val row = array.optJSONObject(i) ?: continue
    val at = row.optLong("at", 0L)
    val said = row.optString("text").trim()
    if (at <= 0L || said.isEmpty()) continue
    val id = row.optString("id").ifBlank { reminderId(at, said) }
    out.add(Reminder(id = id, text = said, atMillis = at))
  }
  return out.sortedBy { it.atMillis }
}

fun storedReminders(reminders: List<Reminder>): String {
  val array = JSONArray()
  reminders.forEach {
    if (it.text.isBlank() || it.atMillis <= 0L) return@forEach
    array.put(JSONObject().put("id", it.id).put("text", it.text.trim()).put("at", it.atMillis))
  }
  return array.toString()
}
