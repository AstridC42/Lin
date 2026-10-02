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

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

/**
 * Release metadata, overridable from CI.
 *
 * The release workflow derives both from the git tag it is building, so tagging `v0.2.0` is the
 * only step needed to cut a release. Local builds fall back to the values checked in here.
 */
val starVersionName: String = System.getenv("STAR_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "0.1.0"
val starVersionCode: Int = System.getenv("STAR_VERSION_CODE")?.toIntOrNull() ?: 1

/**
 * Upload-key material, supplied by CI secrets or a local `keystore.properties`.
 *
 * When nothing is configured the release build falls back to the debug key, so `assembleRelease`
 * still works on a fresh clone - the resulting APK is installable but not upgrade-compatible with
 * a properly signed one.
 */
val releaseKeystoreFile: File? =
    (System.getenv("STAR_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?: run {
          val props = rootProject.file("keystore.properties")
          if (props.exists()) {
            Properties().apply { props.inputStream().use(::load) }.getProperty("storeFile")
          } else {
            null
          }
        })
        ?.let(::File)
        ?.takeIf { it.exists() }

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    // Star Storyteller ships as its own app rather than a drop-in replacement for Edge Gallery,
    // so it installs alongside it instead of colliding with its package name.
    applicationId = "com.starstoryteller.assistant"
    minSdk = 31
    targetSdk = 37
    // Driven by the release workflows rather than hardcoded: both pack the semantic version and
    // a build number into versionCode so test builds and releases keep increasing. Upstream's
    // literals (45 / "1.0.20") would make every build look identical to Android.
    versionCode = starVersionCode
    versionName = starVersionName

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    buildConfigField("String", "FEEDBACK_API_KEY", "\"\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseKeystoreFile != null) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = System.getenv("STAR_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("STAR_KEY_ALIAS")
        keyPassword = System.getenv("STAR_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig =
          signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
    }
  }

  // Ship one APK per ABI plus a universal fallback. The LiteRT and MediaPipe native libraries
  // dominate the download, so a device-matched APK is roughly a third of the universal one.
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64")
      isUniversalApk = true
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.app.projected)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  ksp(libs.hilt.android.compiler)
  ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  annotationProcessor("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(libs.tasks.vision)
}

configurations.all {
  resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        create("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}
