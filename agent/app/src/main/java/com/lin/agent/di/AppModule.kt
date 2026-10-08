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
import com.lin.agent.core.models.ModelStore
import com.lin.agent.core.settings.SettingsRepository
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
  fun provideModelDownloads(@ApplicationContext context: Context): ModelDownloads =
    ModelDownloads(context)
}
