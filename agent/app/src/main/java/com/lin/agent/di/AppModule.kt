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
import com.lin.agent.core.settings.SettingsRepository
import com.lin.agent.engine.FakeBackend
import com.lin.agent.engine.LlmBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides
  @Singleton
  fun provideSettings(@ApplicationContext context: Context): SettingsRepository =
    SettingsRepository(context)

  /**
   * The backend, which is a fake until Phase 1.
   *
   * Bound by interface rather than by type, so swapping a real engine in is a change to this one
   * function. Nothing above this line knows or cares which it got.
   */
  @Provides
  @Singleton
  fun provideBackend(): LlmBackend = FakeBackend(perTokenDelayMillis = 40)
}
