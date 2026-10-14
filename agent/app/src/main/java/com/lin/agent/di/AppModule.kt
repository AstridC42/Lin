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

package com.lin.agent.di

import android.content.Context
import com.lin.agent.core.models.ModelDownloads
import com.lin.agent.core.memory.MemoryStore
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.calendar.Calendars
import com.lin.agent.core.documents.DocumentLibrary
import com.lin.agent.core.documents.Documents
import com.lin.agent.core.documents.PdfPages
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.core.training.TrainingArchive
import com.lin.agent.engine.ModelServer
import com.lin.agent.model.PhoneTool
import com.lin.agent.core.media.PhotoLibrary
import androidx.work.WorkManager
import com.lin.agent.model.ConversationLog
import com.lin.agent.voice.Dictation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides @Singleton
  fun provideSettings(@ApplicationContext context: Context): SettingsRepository =
    SettingsRepository(context)

  @Provides @Singleton
  fun provideModelStore(@ApplicationContext context: Context): ModelStore = ModelStore(context)

  @Provides @Singleton
  fun provideDocuments(@ApplicationContext context: Context): Documents = Documents(context)

  @Provides @Singleton
  fun provideDocumentLibrary(@ApplicationContext context: Context): DocumentLibrary =
    DocumentLibrary(context)

  @Provides @Singleton
  fun providePdfPages(@ApplicationContext context: Context): PdfPages = PdfPages(context)

  @Provides @Singleton
  fun provideTrainingArchive(@ApplicationContext context: Context): TrainingArchive =
    TrainingArchive(context.filesDir)

  @Provides @Singleton
  fun provideCalendars(@ApplicationContext context: Context): Calendars = Calendars(context)

  @Provides @Singleton
  fun provideModelDownloads(@ApplicationContext context: Context): ModelDownloads =
    ModelDownloads(context)

  // Stateless and injected rather than constructed where it is used, so a test can hand the
  // settings screen a server that answers without one existing.
  @Provides @Singleton
  fun provideMemoryStore(@ApplicationContext context: Context): MemoryStore =
    MemoryStore(context)

  @Provides @Singleton
  fun providePhotoLibrary(@ApplicationContext context: Context): PhotoLibrary =
    PhotoLibrary(context)

  @Provides @Singleton
  fun provideDictation(@ApplicationContext context: Context): Dictation =
    Dictation(context)

  @Provides @Singleton
  fun provideConversationLog(@ApplicationContext context: Context): ConversationLog =
    ConversationLog(context)

  @Provides @Singleton
  fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
    WorkManager.getInstance(context)

  @Provides @Singleton fun provideModelServer(): ModelServer = ModelServer()

  @Provides @Singleton
  fun providePhoneTool(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
  ): PhoneTool = PhoneTool(context, settings)
}
