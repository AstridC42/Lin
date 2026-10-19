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

// Lin on the watch: a remote for the Lin on the phone. Same applicationId and signing key as the
// phone app, which is what lets Wear OS's Data Layer connect the two.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val linVersionName: String = System.getenv("LIN_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val linVersionCode: Int = System.getenv("LIN_VERSION_CODE")?.toIntOrNull() ?: 1
val linKeystoreFile: File? =
  System.getenv("LIN_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.lin.agent.watch"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.lin.agent"
    // Wear OS 3, the first Wear OS the Galaxy Watch runs.
    minSdk = 30
    targetSdk = 36
    versionCode = linVersionCode
    versionName = linVersionName
  }

  signingConfigs {
    if (linKeystoreFile != null) {
      create("release") {
        storeFile = linKeystoreFile
        storePassword = System.getenv("LIN_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("LIN_KEY_ALIAS")
        keyPassword = System.getenv("LIN_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      // Shrunk: a watch has little storage, and unshrunk Compose is most of this APK.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig =
        if (linKeystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
  }

  // The one file the watch shares with the phone: what they say to each other. Compiled in
  // rather than depending on :core, which would bring the phone's PDF reader to a watch.
  sourceSets["main"].kotlin.directories.add("../core/src/main/java/com/lin/agent/core/wear")

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

dependencies {
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.input)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.androidx.concurrent.futures)
  // Play services brings an old Fragment; activity results need 1.3 or newer.
  implementation(libs.androidx.fragment)
}
