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

package com.lin.agent.core.phone

/** Somebody in the address book, reduced to what deciding needs. */
data class KnownNumber(val name: String, val number: String, val label: String = "")

/** What should happen when the model asks for a call. */
sealed interface CallDecision {

  /**
   * Put the number in the dialer and stop. The user taps to call.
   *
   * The default, and the whole point of the policy. [why] says which reason stopped it here,
   * because from the outside "not opted in" and "permission not granted" look identical - a
   * dialer with a number in it - and somebody who asked for a call deserves to know which.
   */
  data class OpenDialer(val number: String, val displayName: String, val why: String) :
    CallDecision

  /** Place it. Only ever after an explicit opt-in *and* the permission. */
  data class PlaceCall(val number: String, val displayName: String) : CallDecision

  /** Nothing was dialled; the model has to come back to the user first. */
  data class AskFirst(val question: String) : CallDecision

  data class Refuse(val reason: String) : CallDecision
}

/**
 * Whether a call happens, and how far it gets without the user.
 *
 * The rule this whole file exists to keep: **a call is staged in the dialer unless the user has
 * explicitly opted into direct calls and granted the permission.** A model that misreads a
 * number costs a tap when the call is staged, and costs somebody a wrong call they cannot take
 * back when it is not. The opt-in is not a convenience setting, it is where that risk is
 * accepted on purpose.
 *
 * Both conditions are required and neither implies the other: the permission can be granted by a
 * dialog the user barely read, and the opt-in can be set on a phone where the permission was
 * later revoked in settings.
 */
fun decideCall(
  /** What the model asked to call: digits, or a name to look up. */
  requested: String,
  /** Contacts matching [requested], already looked up. Empty when it is a raw number. */
  matches: List<KnownNumber>,
  /** Every contact worth checking a raw number against. See [looksLikeAGarbledCopy]. */
  addressBook: List<KnownNumber> = emptyList(),
  optedInToDirectCalls: Boolean,
  callPermissionGranted: Boolean,
): CallDecision {
  val asked = requested.trim()
  if (asked.isEmpty()) return CallDecision.Refuse("No number or name was given.")

  // Distinct people, not distinct numbers: one contact with a mobile and a work number is not
  // ambiguous enough to interrupt somebody over.
  val people = matches.distinctBy { it.name.lowercase() }
  if (people.size > 1) {
    return CallDecision.AskFirst(
      "More than one contact matches \"$asked\": " +
        people.joinToString(", ") { "${it.name} (${it.number})" } +
        ". Nothing was dialled. Ask which one, then call again with the number."
    )
  }

  if (people.size == 1) {
    val person = people.first()
    return stage(person.number, person.name, optedInToDirectCalls, callPermissionGranted)
  }

  // No contact matched, so it has to be a number the model produced itself.
  if (!looksLikeANumber(asked)) {
    return CallDecision.Refuse(
      "\"$asked\" is neither a contact nor a phone number. Ask the user who to call."
    )
  }

  // A number that is nearly one in the address book is treated as a slip, not dialled. This is
  // the failure that cannot be taken back: a model transposing two digits produces a number
  // that is perfectly valid and belongs to a stranger, and nobody finds out until they answer.
  addressBook.firstOrNull { looksLikeAGarbledCopy(asked, it.number) }?.let { near ->
    return CallDecision.AskFirst(
      "\"$asked\" is not a number in the contacts, but it is nearly ${near.name}'s " +
        "(${near.number}) with the digits in a different order. Nothing was dialled. Ask the " +
        "user whether they meant ${near.name}."
    )
  }

  return stage(asked, asked, optedInToDirectCalls, callPermissionGranted)
}

private fun stage(
  number: String,
  displayName: String,
  optedIn: Boolean,
  permitted: Boolean,
): CallDecision =
  when {
    optedIn && permitted -> CallDecision.PlaceCall(number, displayName)
    !optedIn ->
      CallDecision.OpenDialer(
        number,
        displayName,
        "Direct calling is off, so the number is in the dialer and the user taps to call. " +
          "They can turn it on in settings.",
      )
    else ->
      CallDecision.OpenDialer(
        number,
        displayName,
        "Direct calling is on but the phone permission was not granted, so the number is in " +
          "the dialer and the user taps to call.",
      )
  }

/** Enough digits to be a phone number rather than a house number or a year. */
private const val MIN_COMPARABLE_DIGITS = 6

internal fun digitsOf(text: String): String = text.filter { it.isDigit() }

/** Whether this could be dialled at all, as opposed to being a name. */
fun looksLikeANumber(text: String): Boolean {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return false
  // Everything a dialled number may contain. A letter anywhere means it is a name.
  if (!trimmed.all { it.isDigit() || it in " +-()." }) return false
  return digitsOf(trimmed).length >= MIN_COMPARABLE_DIGITS
}

/**
 * Whether [given] looks like [known] copied out wrongly.
 *
 * Two failures are caught and they are the ones a language model actually makes: the digits
 * reordered, and a single digit wrong. Either produces a number that is entirely valid and
 * belongs to somebody else.
 *
 * Compared from the right-hand end, so `+972 50 123 4567` and `050 123 4567` are recognised as
 * the same number rather than as a near miss - otherwise every correctly-copied international
 * number would be treated as a slip.
 */
fun looksLikeAGarbledCopy(given: String, known: String): Boolean {
  val a = digitsOf(given)
  val b = digitsOf(known)
  if (a.length < MIN_COMPARABLE_DIGITS || b.length < MIN_COMPARABLE_DIGITS) return false

  val tail = minOf(a.length, b.length)
  val left = a.takeLast(tail)
  val right = b.takeLast(tail)
  if (left == right) return false // Copied correctly.
  if (left.toList().sorted() == right.toList().sorted()) return true // Reordered.

  // Deliberately the whole numbers rather than the tails compared above. Comparing tails here
  // reads +972 50 123 4567 against 050 123 4567 as a one-digit slip - they are the same number
  // written two ways, and every correctly-copied international number would be queried. On the
  // full strings the length difference rules it out, which is what the country code is for.
  return editDistanceAtMostOne(a, b)
}

/** One substitution, insertion or deletion apart. Enough for a mistyped digit. */
internal fun editDistanceAtMostOne(a: String, b: String): Boolean {
  if (kotlin.math.abs(a.length - b.length) > 1) return false
  var i = 0
  var j = 0
  var differences = 0
  while (i < a.length && j < b.length) {
    if (a[i] == b[j]) {
      i++
      j++
      continue
    }
    if (++differences > 1) return false
    when {
      a.length > b.length -> i++
      a.length < b.length -> j++
      else -> {
        i++
        j++
      }
    }
  }
  return differences + (a.length - i) + (b.length - j) <= 1
}
