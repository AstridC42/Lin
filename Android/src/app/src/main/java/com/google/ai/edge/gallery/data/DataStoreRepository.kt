/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.data

import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.AssistantMemory
import com.google.ai.edge.gallery.proto.AssistantSettings
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.BenchmarkResults
import com.google.ai.edge.gallery.proto.Cutout
import com.google.ai.edge.gallery.proto.CutoutCollection
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Reminders
import com.google.ai.edge.gallery.proto.Settings
import com.google.ai.edge.gallery.proto.SmartHomeSettings
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.proto.Skills
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.proto.WebSearchSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

interface DataStoreRepository {
  fun saveTextInputHistory(history: List<String>)

  fun readTextInputHistory(): List<String>

  fun saveTheme(theme: Theme)

  fun readTheme(): Theme

  /**
   * Saves the user's preference for whether Firebase Analytics data collection is enabled (`true`)
   * or disabled (`false`).
   *
   * Note that this preference is stored internally on the Settings proto as
   * `disable_firebase_analytics = !enabled`. This ensures that when the proto field is unset (its
   * default value being `false`), data collection remains enabled by default across new installs
   * and upgrades until explicitly toggled off by the user.
   *
   * @param enabled `true` to enable Firebase Analytics data collection; `false` to disable it.
   */
  fun saveFirebaseAnalytics(enabled: Boolean)

  /**
   * Reads that current setting for whether Firebase Analytics data collection is enabled.
   *
   * @return `true` if analytics is enabled or has not been explicitly disabled by the user; `false`
   *   otherwise.
   */
  fun readFirebaseAnalytics(): Boolean

  fun saveSecret(key: String, value: String)

  fun readSecret(key: String): String?

  fun deleteSecret(key: String)

  fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)

  fun clearAccessTokenData()

  fun readAccessTokenData(): AccessTokenData?

  fun saveImportedModels(importedModels: List<ImportedModel>)

  fun readImportedModels(): List<ImportedModel>

  fun isTosAccepted(): Boolean

  fun acceptTos()

  fun isGemmaTermsOfUseAccepted(): Boolean

  fun acceptGemmaTermsOfUse()

  fun getHasRunTinyGarden(): Boolean

  fun setHasRunTinyGarden(hasRun: Boolean)

  fun addCutout(cutout: Cutout)

  fun getAllCutouts(): List<Cutout>

  fun setCutout(newCutout: Cutout)

  fun setCutouts(cutouts: List<Cutout>)

  fun setHasSeenBenchmarkComparisonHelp(seen: Boolean)

  fun getHasSeenBenchmarkComparisonHelp(): Boolean

  fun addBenchmarkResult(result: BenchmarkResult)

  fun getAllBenchmarkResults(): List<BenchmarkResult>

  fun deleteBenchmarkResult(index: Int)

  fun addSkill(skill: Skill)

  fun setSkills(skills: List<Skill>)

  fun setSkillSelected(skill: Skill, selected: Boolean)

  fun setAllSkillsSelected(selected: Boolean)

  fun getAllSkills(): List<Skill>

  fun deleteSkill(name: String)

  suspend fun deleteSkills(names: Set<String>)

  /** Records that a promo with the specified ID has been viewed. */
  fun addViewedPromoId(promoId: String)

  /** Removes a viewed promo record. */
  fun removeViewedPromoId(promoId: String)

  /** Returns whether a promo with the specified ID has been viewed. */
  fun hasViewedPromo(promoId: String): Boolean

  /** Reads the SearXNG web search connection settings. */
  fun readWebSearchSettings(): WebSearchSettings

  /** Persists the SearXNG web search connection settings. */
  fun saveWebSearchSettings(settings: WebSearchSettings)

  /** Reads the preferences of the unified assistant screen. */
  fun readAssistantSettings(): AssistantSettings

  /** Persists the preferences of the unified assistant screen. */
  fun saveAssistantSettings(settings: AssistantSettings)

  /** What the assistant has been told to remember about the user. */
  fun readAssistantMemory(): AssistantMemory

  /** Persists what the assistant remembers. */
  fun saveAssistantMemory(memory: AssistantMemory)

  /** Reminders the assistant delivers itself. */
  fun readReminders(): Reminders

  /** Persists the assistant's reminders. */
  fun saveReminders(reminders: Reminders)

  /** Home Assistant connection settings. */
  fun readSmartHomeSettings(): SmartHomeSettings

  /** Persists the Home Assistant connection settings. */
  fun saveSmartHomeSettings(settings: SmartHomeSettings)
}

/** Repository for managing data using Proto DataStore. */
class DefaultDataStoreRepository(
  private val dataStore: DataStore<Settings>,
  private val userDataDataStore: DataStore<UserData>,
  private val cutoutDataStore: DataStore<CutoutCollection>,
  private val benchmarkResultsDataStore: DataStore<BenchmarkResults>,
  private val skillsDataStore: DataStore<Skills>,
) : DataStoreRepository {
  override fun saveTextInputHistory(history: List<String>) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().clearTextInputHistory().addAllTextInputHistory(history).build()
      }
    }
  }

  override fun readTextInputHistory(): List<String> {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.textInputHistoryList
    }
  }

  override fun saveTheme(theme: Theme) {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setTheme(theme).build() }
    }
  }

  /**
   * The saved theme, or the automatic one when the store cannot be read.
   *
   * Read fresh rather than through `settings()`: the snapshot there is only kept in step by the
   * writers that go through `updateSettings`, and `saveTheme` below writes the store directly, so
   * reading this field from the snapshot could return a theme the user has since changed.
   *
   * Guarded because this is called from `Application.onCreate`, where a throw is not a crash
   * anybody can report - it is an app that closes before it has drawn a single pixel, and closes
   * again the next time, because whatever made the read fail is on the disk.
   */
  override fun readTheme(): Theme {
    val curTheme =
      try {
        runBlocking { dataStore.data.first() }.theme
      } catch (e: Throwable) {
        StoreHealth.recordReadFailure("settings", e)
        return Theme.THEME_AUTO
      }
    // Use "auto" as the default theme.
    return if (curTheme == Theme.THEME_UNSPECIFIED) Theme.THEME_AUTO else curTheme
  }

  /**
   * Persists the inverted value (`!enabled`) into `settings.disableFirebaseAnalytics` within proto
   * DataStore.
   */
  override fun saveFirebaseAnalytics(enabled: Boolean) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setDisableFirebaseAnalytics(!enabled).build()
      }
    }
  }

  /**
   * Reads `settings.disableFirebaseAnalytics` from proto DataStore and returns the inverted value
   * so that `false` (default uninitialized value) evaluates to `true` (enabled).
   */
  /** Also read in `Application.onCreate`, and guarded for the same reason as [readTheme]. */
  override fun readFirebaseAnalytics(): Boolean =
    try {
      !runBlocking { dataStore.data.first() }.disableFirebaseAnalytics
    } catch (e: Throwable) {
      StoreHealth.recordReadFailure("settings", e)
      // Analytics off is the safe default for a read that failed: it is the choice that does
      // nothing, and it is what a fresh install has.
      false
    }

  override fun saveSecret(key: String, value: String) {
    runBlocking {
      userDataDataStore.updateData { userData ->
        userData.toBuilder().putSecrets(key, value).build()
      }
    }
  }

  override fun readSecret(key: String): String? {
    return runBlocking { userDataDataStore.data.first().secretsMap[key] }
  }

  override fun deleteSecret(key: String) {
    runBlocking {
      userDataDataStore.updateData { userData -> userData.toBuilder().removeSecrets(key).build() }
    }
  }

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    runBlocking {
      // Clear the entry in old data store.
      dataStore.updateData { settings ->
        settings.toBuilder().setAccessTokenData(AccessTokenData.getDefaultInstance()).build()
      }

      userDataDataStore.updateData { userData ->
        userData
          .toBuilder()
          .setAccessTokenData(
            AccessTokenData.newBuilder()
              .setAccessToken(accessToken)
              .setRefreshToken(refreshToken)
              .setExpiresAtMs(expiresAt)
              .build()
          )
          .build()
      }
    }
  }

  override fun clearAccessTokenData() {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().clearAccessTokenData().build() }
      userDataDataStore.updateData { userData ->
        userData.toBuilder().clearAccessTokenData().build()
      }
    }
  }

  override fun readAccessTokenData(): AccessTokenData? {
    return runBlocking {
      val userData = userDataDataStore.data.first()
      userData.accessTokenData
    }
  }

  override fun saveImportedModels(importedModels: List<ImportedModel>) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().clearImportedModel().addAllImportedModel(importedModels).build()
      }
    }
  }

  override fun readImportedModels(): List<ImportedModel> {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.importedModelList
    }
  }

  override fun isTosAccepted(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.isTosAccepted
    }
  }

  override fun acceptTos() {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setIsTosAccepted(true).build() }
    }
  }

  override fun isGemmaTermsOfUseAccepted(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.isGemmaTermsAccepted
    }
  }

  override fun acceptGemmaTermsOfUse() {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setIsGemmaTermsAccepted(true).build()
      }
    }
  }

  override fun getHasRunTinyGarden(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.hasRunTinyGarden
    }
  }

  override fun setHasRunTinyGarden(hasRun: Boolean) {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setHasRunTinyGarden(hasRun).build() }
    }
  }

  override fun addCutout(cutout: Cutout) {
    runBlocking {
      cutoutDataStore.updateData { cutouts -> cutouts.toBuilder().addCutout(cutout).build() }
    }
  }

  override fun getAllCutouts(): List<Cutout> {
    return runBlocking { cutoutDataStore.data.first().cutoutList }
  }

  override fun setCutout(newCutout: Cutout) {
    runBlocking {
      cutoutDataStore.updateData { cutouts ->
        var index = -1
        for (i in 0..<cutouts.cutoutCount) {
          val cutout = cutouts.cutoutList.get(i)
          if (cutout.id == newCutout.id) {
            index = i
            break
          }
        }
        if (index >= 0) {
          cutouts.toBuilder().setCutout(index, newCutout).build()
        } else {
          cutouts
        }
      }
    }
  }

  override fun setCutouts(cutouts: List<Cutout>) {
    runBlocking {
      cutoutDataStore.updateData { CutoutCollection.newBuilder().addAllCutout(cutouts).build() }
    }
  }

  override fun setHasSeenBenchmarkComparisonHelp(seen: Boolean) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setHasSeenBenchmarkComparisonHelp(seen).build()
      }
    }
  }

  override fun getHasSeenBenchmarkComparisonHelp(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.hasSeenBenchmarkComparisonHelp
    }
  }

  override fun addBenchmarkResult(result: BenchmarkResult) {
    runBlocking {
      benchmarkResultsDataStore.updateData { results ->
        results.toBuilder().addResult(0, result).build()
      }
    }
  }

  override fun getAllBenchmarkResults(): List<BenchmarkResult> {
    return runBlocking { benchmarkResultsDataStore.data.first().resultList }
  }

  override fun deleteBenchmarkResult(index: Int) {
    runBlocking {
      benchmarkResultsDataStore.updateData { results ->
        val newResults = results.toBuilder().removeResult(index).build()
        newResults
      }
    }
  }

  override fun addSkill(skill: Skill) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills = buildList {
          add(skill)
          addAll(skills.skillList)
        }
        skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
      }
    }
  }

  override fun setSkills(skills: List<Skill>) {
    runBlocking {
      skillsDataStore.updateData { curSkills ->
        curSkills.toBuilder().clearSkill().addAllSkill(skills).build()
      }
    }
  }

  override fun setSkillSelected(skill: Skill, selected: Boolean) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills =
          skills.skillList.map { curSkill ->
            if (curSkill.name == skill.name) {
              curSkill.toBuilder().setSelected(selected).setUserModifiedSelection(true).build()
            } else {
              curSkill
            }
          }
        Skills.newBuilder().addAllSkill(newSkills).build()
      }
    }
  }

  override fun setAllSkillsSelected(selected: Boolean) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills =
          skills.skillList.map { curSkill ->
            curSkill.toBuilder().setSelected(selected).setUserModifiedSelection(true).build()
          }
        Skills.newBuilder().addAllSkill(newSkills).build()
      }
    }
  }

  override fun getAllSkills(): List<Skill> {
    return runBlocking { skillsDataStore.data.first().skillList }
  }

  override fun deleteSkill(name: String) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills = skills.skillList.filter { it.name != name }
        Skills.newBuilder().addAllSkill(newSkills).build()
      }
    }
  }

  override suspend fun deleteSkills(names: Set<String>) {
    skillsDataStore.updateData { skills ->
      val newSkills = skills.skillList.filter { it.name !in names }
      skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
    }
  }

  override fun addViewedPromoId(promoId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        if (settings.viewedPromoIdList.contains(promoId)) {
          settings
        } else {
          settings.toBuilder().addViewedPromoId(promoId).build()
        }
      }
    }
  }

  override fun removeViewedPromoId(promoId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        val newList = settings.viewedPromoIdList.filter { it != promoId }
        settings.toBuilder().clearViewedPromoId().addAllViewedPromoId(newList).build()
      }
    }
  }

  override fun hasViewedPromo(promoId: String): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.viewedPromoIdList.contains(promoId)
    }
  }

  /**
   * The last settings read from disk, kept so the four readers below do not go back for it.
   *
   * The rest of this class reads through to the store on every call, which is fine for settings
   * that are read once at startup. These four are not: the assistant screen reads its preferences
   * several times while composing, `isConfigured()` is asked on every tool call and again on every
   * turn while the system prompt is rebuilt, and each of those is `runBlocking` around a disk read
   * on whichever thread asked - the main thread during composition, the inference thread during a
   * turn. That is the shape of a stutter at launch and, on a slow filesystem, of an ANR.
   *
   * Correctness rests on one thing: nothing outside this class writes these four fields, and the
   * four writers below all refresh this from what the store returns. A write to some other field
   * of the same proto does not invalidate it, because `updateData` always starts from the real
   * current value on disk rather than from anything cached here.
   */
  @Volatile private var settingsSnapshot: Settings? = null

  /**
   * The stored settings, or their defaults when the store cannot be read at all.
   *
   * Reading preferences must not be able to close the app. A corrupt file is already handled where
   * the store is created, by resetting it; this covers everything else that can make a read fail,
   * of which a full disk is the likely one - the user has just downloaded several gigabytes of
   * model weights, and the first thing they do afterwards is open the app.
   *
   * Three of these reads happen before the app has drawn anything: the theme and the analytics
   * flag in `Application.onCreate`, and the assistant's settings, to find out whether the previous
   * launch died while loading a model. A throw from any of them is not a crash the user can report
   * - it is an app that will not start, and will not start on the next attempt either, because the
   * cause is sitting on the disk. Defaults are a bad outcome. A phone with an app on it that
   * cannot be opened is a worse one.
   *
   * Deliberately not cached on failure: `settingsSnapshot` stays null, so the next read tries the
   * disk again rather than pinning defaults in memory for the rest of the process. Whatever made
   * the read fail may well have passed by then.
   */
  private fun settings(): Settings {
    settingsSnapshot?.let {
      return it
    }
    return try {
      runBlocking { dataStore.data.first() }.also { settingsSnapshot = it }
    } catch (e: Throwable) {
      StoreHealth.recordReadFailure("settings", e)
      Settings.getDefaultInstance()
    }
  }

  /**
   * Applies [change] to the stored settings and keeps the snapshot in step with the result.
   *
   * A write that fails is reported and dropped rather than thrown. The call sites are switches and
   * text fields in settings dialogs, and on a full disk every one of them would otherwise take the
   * app down as it was touched.
   */
  private fun updateSettings(change: (Settings.Builder) -> Settings.Builder) {
    try {
      runBlocking {
        settingsSnapshot = dataStore.updateData { current -> change(current.toBuilder()).build() }
      }
    } catch (e: Throwable) {
      StoreHealth.recordWriteFailure("settings", e)
    }
  }

  override fun readWebSearchSettings(): WebSearchSettings = settings().webSearchSettings

  override fun saveWebSearchSettings(settings: WebSearchSettings) {
    updateSettings { it.setWebSearchSettings(settings) }
  }

  override fun readAssistantSettings(): AssistantSettings = settings().assistantSettings

  override fun saveAssistantSettings(settings: AssistantSettings) {
    updateSettings { it.setAssistantSettings(settings) }
  }

  override fun readAssistantMemory(): AssistantMemory = settings().assistantMemory

  override fun saveAssistantMemory(memory: AssistantMemory) {
    updateSettings { it.setAssistantMemory(memory) }
  }

  override fun readReminders(): Reminders = settings().reminders

  override fun saveReminders(reminders: Reminders) {
    updateSettings { it.setReminders(reminders) }
  }

  override fun readSmartHomeSettings(): SmartHomeSettings = settings().smartHomeSettings

  override fun saveSmartHomeSettings(settings: SmartHomeSettings) {
    updateSettings { it.setSmartHomeSettings(settings) }
  }
}
