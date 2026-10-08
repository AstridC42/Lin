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
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.lin.agent.engine"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }
  defaultConfig { minSdk = 31 }
  // android.util.Log and Build throw "not mocked" in a plain JVM test, which fails tests that
  // merely log on a path they are exercising. Returning defaults instead keeps the logic testable
  // without pulling in Robolectric for the sake of two static methods - and without having to
  // strip logging out of code that benefits from it on a device.
  testOptions { unitTests.isReturnDefaultValues = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
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
  implementation(libs.androidx.core.ktx)
  // The only module that knows what a LiteRT-LM Engine is. That boundary is the point of
  // having this module at all.
  implementation(libs.litertlm)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // org.json ships inside Android, so the app needs no dependency for it - but the android.jar
  // a unit test compiles against is stubbed, and every stubbed method throws. Without a real
  // implementation on the test classpath the JSON parsing would be the one part of the server
  // client that could not be tested, which is also the part most likely to be wrong. Test-only,
  // so nothing reaches the APK.
  testImplementation(libs.org.json)
}
