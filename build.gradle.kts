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

// Patched versions of libraries that come in transitively, mostly with the build tools
// (Android Gradle Plugin, lint, the test platform) rather than in the app. Each line closes a
// known vulnerability; see "Dependencies" in SECURITY.md. Applied to the build's own classpath here and to
// every project's configurations below.
buildscript {
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      val name = requested.name
      when (requested.group) {
        "io.netty" -> if (name.startsWith("netty-") && !name.startsWith("netty-tcnative")) useVersion("4.1.137.Final")
        "com.google.guava" -> if (name == "guava") useVersion("33.7.2-jre")
        "com.google.protobuf" ->
          when (name) {
            "protobuf-java", "protobuf-java-util", "protobuf-kotlin" ->
              if (requested.version.orEmpty().startsWith("3.")) useVersion("3.25.5")
            "protobuf-javalite", "protobuf-kotlin-lite" ->
              if (requested.version.orEmpty().startsWith("3.")) useVersion("3.25.5") else useVersion("4.28.2")
          }
        "org.bouncycastle" -> if (name.endsWith("-jdk18on")) useVersion("1.85")
        "org.apache.commons" -> if (name == "commons-lang3") useVersion("3.18.0")
        "org.apache.httpcomponents" -> if (name == "httpclient") useVersion("4.5.14")
        "org.bitbucket.b_c" -> if (name == "jose4j") useVersion("0.9.6")
        "org.jdom" -> if (name == "jdom2") useVersion("2.0.6.1")
      }
    }
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.hilt.application) apply false
  alias(libs.plugins.ksp) apply false
}

allprojects {
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      val name = requested.name
      when (requested.group) {
        "io.netty" -> if (name.startsWith("netty-") && !name.startsWith("netty-tcnative")) useVersion("4.1.137.Final")
        // The Android flavour where the app uses it, the JRE one in the build tools.
        "com.google.guava" ->
          if (name == "guava") useVersion(if (requested.version.orEmpty().endsWith("-android")) "33.7.2-android" else "33.7.2-jre")
        "com.google.protobuf" ->
          when (name) {
            "protobuf-java", "protobuf-java-util", "protobuf-kotlin" ->
              if (requested.version.orEmpty().startsWith("3.")) useVersion("3.25.5")
            "protobuf-javalite", "protobuf-kotlin-lite" ->
              if (requested.version.orEmpty().startsWith("3.")) useVersion("3.25.5") else useVersion("4.28.2")
          }
        "org.bouncycastle" -> if (name.endsWith("-jdk18on")) useVersion("1.85")
        "org.apache.commons" -> if (name == "commons-lang3") useVersion("3.18.0")
        "org.apache.httpcomponents" -> if (name == "httpclient") useVersion("4.5.14")
        "org.bitbucket.b_c" -> if (name == "jose4j") useVersion("0.9.6")
        "org.jdom" -> if (name == "jdom2") useVersion("2.0.6.1")
      }
    }
  }
}
