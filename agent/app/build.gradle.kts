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

/**
 * Version, supplied by the release workflow.
 *
 * The fallbacks are what a local build gets. CI packs the semantic version and a build number into
 * versionCode so that every build Android sees is newer than the last - literals here would make
 * them all look identical and block the upgrade.
 */
val linVersionName: String = System.getenv("LIN_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val linVersionCode: Int = System.getenv("LIN_VERSION_CODE")?.toIntOrNull() ?: 1

/**
 * The signing key, supplied by CI.
 *
 * This matters more than it looks. A downloaded model lives in the app's own files directory, so
 * uninstalling takes two and a half gigabytes with it - which means builds that cannot be
 * installed over each other cost a full re-download every time. Debug keys are generated per
 * machine, so CI has to sign with one stable key or every update is an uninstall.
 *
 * Without one configured, the release build falls back to the debug key: `assembleRelease` still
 * works on a fresh clone, and the result is installable but not upgrade-compatible.
 */
val linKeystoreFile: File? =
    System.getenv("LIN_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }

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
    versionCode = linVersionCode
    versionName = linVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
      isMinifyEnabled = false
      signingConfig =
        if (linKeystoreFile != null) signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
    }
  }

  // One APK per ABI, plus a universal fallback. The LiteRT native libraries dominate the download
  // - the universal APK is around 87 MB and a device-matched one is closer to 40 - and on a phone
  // connection that difference is worth the extra build time.
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64")
      isUniversalApk = true
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

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    // LiteRT-LM 0.18 ships against the Kotlin 2.4.0 stdlib, whose metadata version this
    // compiler (2.2.21) refuses to read. The archived fork carries the same flag for the same
    // reason, which is how this toolchain is known to work with these models.
    //
    // It suppresses a real check, so it is worth stating what the risk is: if the library used a
    // 2.4 language feature in its public API, this would compile and fail at runtime. Bumping
    // Kotlin instead would mean moving AGP and KSP too, which is a bigger change than this app
    // should make before it has run on a phone once.
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(project(":core"))
  implementation(project(":engine"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  // ProcessLifecycleOwner, for one question: does this app have a window on screen? It decides
  // whether the wake word may open the panel at all - see WakeRoute. Already on the classpath
  // through something else, and declared here because a transitive dependency is not a promise.
  implementation(libs.androidx.lifecycle.process)
  // The hinge, through Jetpack rather than Samsung's SDK: the same answer on every foldable,
  // and already on the classpath through material3-adaptive. Declared because Fold.kt reads
  // it directly, and a transitive dependency is not a promise.
  implementation(libs.androidx.window)
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
