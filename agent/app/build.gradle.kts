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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.lin.agent"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    // A new id, not a replacement for the archived fork's com.lin.assistant, so
    // both install side by side while this one is still a skeleton.
    applicationId = "com.lin.agent"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  // Note for later: AGP 9 generates unit tests for one build type only, and it
  // defaults to debug - which is what CI runs here. When releases start, this
  // needs testBuildType = "release" or the release test task will not exist.
  // The fork's CI failed on exactly that.

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

dependencies {
  implementation(project(":core"))
  implementation(project(":engine"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.core)
  implementation(libs.androidx.material3.adaptive)
  implementation(libs.androidx.splashscreen)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  ksp(libs.hilt.android.compiler)
  debugImplementation(libs.androidx.ui.tooling)

  testImplementation(libs.junit)
}
