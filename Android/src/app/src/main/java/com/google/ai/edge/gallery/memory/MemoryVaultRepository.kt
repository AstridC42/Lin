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

package com.google.ai.edge.gallery.memory

import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.proto.ConversationDigest
import com.google.ai.edge.gallery.proto.MemoryNote
import com.google.ai.edge.gallery.proto.MemoryVault
import com.google.ai.edge.gallery.proto.NoteKind
import com.google.ai.edge.gallery.proto.PendingDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** A hit from searching the vault, whatever layer it came from. */
data class MemoryHit(
  val kind: Kind,
  val id: String,
  val title: String,
  val body: String,
  val whenMillis: Long,
  /** Only for conversations: where the full transcript is. */
  val archivePath: String = "",
) {
  enum class Kind {
    NOTE,
    CONVERSATION,
  }
}

/**
 * The one place that reads and writes what Lin remembers.
 *
 * Everything goes through here rather than through the DataStore directly, for one reason that
 * shows up only under concurrency: the digester runs in a background worker while the user may
 * be editing the same vault on screen. `updateData` serialises those, so every change is written
 * as read-modify-write inside it rather than as a read followed by a write that clobbers
 * whatever landed in between.
 */
@Singleton
class MemoryVaultRepository
@Inject
constructor(private val dataStore: DataStore<MemoryVault>) {

  val vault: Flow<MemoryVault> = dataStore.data

  suspend fun read(): MemoryVault = dataStore.data.first()

  // ---------------------------------------------------------------- notes

  /** The notes worth putting in front of the model this turn. */
  suspend fun notesForPrompt(nowMillis: Long): List<MemoryNote> =
    MemoryRetention.forPrompt(read().noteList, nowMillis)

  /** Adds something the user said to remember. Returns the note, or null if there was nothing. */
  suspend fun addUserNote(
    text: String,
    kind: NoteKind = NoteKind.NOTE_KIND_CONTEXT,
    nowMillis: Long,
  ): MemoryNote? {
    val tidy = NoteMerge.tidy(text) ?: return null
    var result: MemoryNote? = null
    dataStore.updateData { vault ->
      val existing = vault.noteList.indexOfFirst { NoteMerge.saysTheSameThing(it.text, tidy) }
      val notes = vault.noteList.toMutableList()
      if (existing >= 0) {
        // Saying it again out loud is the user confirming it, and makes it theirs rather than
        // the model's inference.
        val updated =
          notes[existing]
            .toBuilder()
            .setUserWritten(true)
            .setCold(false)
            .setLastConfirmedAtMillis(nowMillis)
            .build()
        notes[existing] = updated
        result = updated
      } else {
        val note =
          MemoryNote.newBuilder()
            .setId(newId())
            .setText(tidy)
            .setKind(kind)
            .setCreatedAtMillis(nowMillis)
            .setLastConfirmedAtMillis(nowMillis)
            .setReinforcementCount(1)
            .setUserWritten(true)
            .build()
        notes.add(note)
        result = note
      }
      vault.toBuilder().clearNote().addAllNote(notes).build()
    }
    return result
  }

  /** Removes a note outright. The user asked; it goes. */
  suspend fun deleteNote(id: String): Boolean {
    var removed = false
    dataStore.updateData { vault ->
      val notes = vault.noteList.filterNot { it.id == id }
      removed = notes.size != vault.noteCount
      vault.toBuilder().clearNote().addAllNote(notes).build()
    }
    return removed
  }

  /**
   * Removes the one note matching [wording], or null when that is not a single note.
   *
   * Refusing an ambiguous instruction rather than guessing: erasing the wrong memory is not
   * something the user can see has happened.
   */
  suspend fun forgetMatching(wording: String): MemoryNote? {
    val tidy = NoteMerge.tidy(wording) ?: return null
    val candidates = read().noteList.filter { NoteMerge.saysTheSameThing(it.text, tidy) }
    if (candidates.size != 1) return null
    val note = candidates.first()
    return if (deleteNote(note.id)) note else null
  }

  suspend fun setPinned(id: String, pinned: Boolean) {
    dataStore.updateData { vault ->
      val notes =
        vault.noteList.map {
          if (it.id == id) it.toBuilder().setPinned(pinned).setCold(false).build() else it
        }
      vault.toBuilder().clearNote().addAllNote(notes).build()
    }
  }

  /** Marks notes as confirmed again, for when something cold turns out to still be true. */
  suspend fun touchNotes(ids: Collection<String>, nowMillis: Long) {
    if (ids.isEmpty()) return
    val wanted = ids.toSet()
    dataStore.updateData { vault ->
      val notes =
        vault.noteList.map {
          if (it.id in wanted)
            it.toBuilder().setLastConfirmedAtMillis(nowMillis).setCold(false).build()
          else it
        }
      vault.toBuilder().clearNote().addAllNote(notes).build()
    }
  }

  // ------------------------------------------------------------- digesting

  /** Puts a finished conversation in the queue to be summarised. Idempotent. */
  suspend fun queueForDigest(conversationId: String, nowMillis: Long) {
    if (conversationId.isBlank()) return
    dataStore.updateData { vault ->
      val alreadyQueued = vault.pendingList.any { it.conversationId == conversationId }
      val alreadyDone = vault.digestList.any { it.id == conversationId && !it.extractive }
      if (alreadyQueued || alreadyDone) {
        vault
      } else {
        vault
          .toBuilder()
          .addPending(
            PendingDigest.newBuilder()
              .setConversationId(conversationId)
              .setQueuedAtMillis(nowMillis)
              .build()
          )
          .build()
      }
    }
  }

  suspend fun pending(): List<PendingDigest> = read().pendingList

  /** Records that digesting [conversationId] was tried and could not finish. */
  suspend fun recordDigestAttempt(conversationId: String) {
    dataStore.updateData { vault ->
      val pending =
        vault.pendingList.map {
          if (it.conversationId == conversationId)
            it.toBuilder().setAttempts(it.attempts + 1).build()
          else it
        }
      vault.toBuilder().clearPending().addAllPending(pending).build()
    }
  }

  suspend fun dropPending(conversationId: String) {
    dataStore.updateData { vault ->
      val pending = vault.pendingList.filterNot { it.conversationId == conversationId }
      vault.toBuilder().clearPending().addAllPending(pending).build()
    }
  }

  /**
   * Stores what a conversation came to, and the notes it produced, in one write.
   *
   * One write rather than several because a digest linking to note ids that were not saved is a
   * broken vault, and the crash that interrupts the second write is exactly the moment nobody
   * is watching.
   *
   * Deliberately leaves the queue alone. An extractive digest is recorded through here too, and
   * that conversation still wants digesting properly later - so whether the queue entry goes is
   * the caller's decision, made once, where the reason for it is visible.
   */
  suspend fun recordDigest(
    conversationId: String,
    parsed: ParsedDigest,
    archivePath: String,
    messageCount: Int,
    modelName: String,
    startedAtMillis: Long,
    endedAtMillis: Long,
    extractive: Boolean,
    nowMillis: Long,
  ) {
    dataStore.updateData { vault ->
      val merged =
        NoteMerge.merge(
          existing = vault.noteList,
          candidates = parsed.notes,
          conversationId = conversationId,
          nowMillis = nowMillis,
          newId = ::newId,
        )

      val digest =
        ConversationDigest.newBuilder()
          .setId(conversationId)
          .setTitle(parsed.title)
          .setSummary(parsed.summary)
          .addAllTopic(parsed.topics)
          .addAllNoteId(merged.touchedNoteIds)
          .setArchivePath(archivePath)
          .setMessageCount(messageCount)
          .setModelName(modelName)
          .setStartedAtMillis(startedAtMillis)
          .setEndedAtMillis(endedAtMillis)
          .setExtractive(extractive)
          .build()

      val digests =
        (vault.digestList.filterNot { it.id == conversationId } + digest)
          .sortedByDescending { it.endedAtMillis }
          .take(MemoryRetention.MAX_DIGESTS)

      vault
        .toBuilder()
        .clearNote()
        .addAllNote(MemoryRetention.capped(merged.notes, nowMillis))
        .clearDigest()
        .addAllDigest(digests)
        .build()
    }
  }

  // --------------------------------------------------------------- reading

  suspend fun digest(conversationId: String): ConversationDigest? =
    read().digestList.firstOrNull { it.id == conversationId }

  suspend fun recentDigests(limit: Int): List<ConversationDigest> =
    read().digestList.sortedByDescending { it.endedAtMillis }.take(limit)

  /**
   * Searches notes and conversation summaries for [query].
   *
   * Word overlap rather than substring, so "what did I say about the car" reaches a note about
   * "the car needs a service" without needing the phrasing to match. Cold notes are included:
   * being searched for is the whole reason cold storage exists.
   */
  suspend fun search(query: String, limit: Int = 8): List<MemoryHit> {
    val terms = NoteMerge.significantWords(query)
    if (terms.isEmpty()) return emptyList()
    val vault = read()

    fun score(text: String): Int = NoteMerge.significantWords(text).count { it in terms }

    val noteHits =
      vault.noteList
        .map { it to score(it.text) }
        .filter { it.second > 0 }
        .map { (note, s) ->
          s to
            MemoryHit(
              kind = MemoryHit.Kind.NOTE,
              id = note.id,
              title = if (note.cold) "note (cold)" else "note",
              body = note.text,
              whenMillis = note.lastConfirmedAtMillis,
            )
        }

    val digestHits =
      vault.digestList
        .map { it to maxOf(score(it.title), score(it.summary), score(it.topicList.joinToString(" "))) }
        .filter { it.second > 0 }
        .map { (digest, s) ->
          s to
            MemoryHit(
              kind = MemoryHit.Kind.CONVERSATION,
              id = digest.id,
              title = digest.title.ifBlank { "Conversation" },
              body = digest.summary,
              whenMillis = digest.endedAtMillis,
              archivePath = digest.archivePath,
            )
        }

    return (noteHits + digestHits)
      .sortedWith(compareByDescending<Pair<Int, MemoryHit>> { it.first }.thenByDescending { it.second.whenMillis })
      .take(limit)
      .map { it.second }
  }

  // ------------------------------------------------------------ maintenance

  /** Brings the warm/cold flags up to date. Cheap, and a no-op when nothing changed. */
  suspend fun sweep(nowMillis: Long) {
    dataStore.updateData { vault ->
      val swept = MemoryRetention.sweep(vault.noteList, nowMillis)
      if (swept == vault.noteList && vault.lastSweepAtMillis > 0) {
        vault
      } else {
        vault
          .toBuilder()
          .clearNote()
          .addAllNote(MemoryRetention.capped(swept, nowMillis))
          .setLastSweepAtMillis(nowMillis)
          .build()
      }
    }
  }

  /** Carries the old twenty-fact list into the vault, once. */
  suspend fun migrateLegacyFacts(facts: List<Pair<String, Long>>) {
    dataStore.updateData { vault ->
      if (vault.migratedLegacyFacts) return@updateData vault
      val notes = vault.noteList.toMutableList()
      for ((text, savedAt) in facts) {
        val tidy = NoteMerge.tidy(text) ?: continue
        if (notes.any { NoteMerge.saysTheSameThing(it.text, tidy) }) continue
        notes.add(
          MemoryNote.newBuilder()
            .setId(newId())
            .setText(tidy)
            .setKind(NoteKind.NOTE_KIND_CONTEXT)
            .setCreatedAtMillis(savedAt)
            // Carried over as user-written, because that is what the old list was: things the
            // user asked to have remembered. They must not cool off on arrival.
            .setLastConfirmedAtMillis(savedAt)
            .setReinforcementCount(1)
            .setUserWritten(true)
            .build()
        )
      }
      vault.toBuilder().clearNote().addAllNote(notes).setMigratedLegacyFacts(true).build()
    }
  }

  /** Everything. Used by the "forget all of it" action, which also clears the archive. */
  suspend fun clear() {
    dataStore.updateData { MemoryVault.getDefaultInstance().toBuilder().build() }
  }

  private fun newId(): String = UUID.randomUUID().toString().take(12)
}
