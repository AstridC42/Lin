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

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // sherpa-onnx (Lin's own voice and hearing) is published through JitPack. Limited to that
    // one group, so nothing else can ever be resolved from it.
    maven("https://jitpack.io") { content { includeGroup("com.github.k2-fsa.sherpa-onnx") } }
  }
}

rootProject.name = "Lin"

// Three modules and no more. The one boundary worth enforcing is that nothing
// outside :engine knows what a LiteRT-LM Engine is; everything above it talks
// to an interface. Over-modularising a solo project early is its own trap.
include(":app")
include(":core")
include(":engine")
include(":wear")
