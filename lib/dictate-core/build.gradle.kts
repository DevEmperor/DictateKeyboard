import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Copyright (C) 2026 The Dictate Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Shared transcription/LLM core (issue #106): the OpenAI-compatible network provider, the provider
// presets, the data models and the prompt defaults — everything the phone app AND the Wear OS app
// need to talk to a transcription/chat endpoint. Deliberately free of any FlorisBoard IME, JetPref
// or on-device sherpa-onnx coupling so it can be consumed by the lightweight :wear module.
//
// The Kotlin packages here intentionally keep the original `dev.patrickgold.florisboard.dictate.*`
// names so moving these files out of :app needs zero import changes in the phone app.

plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.serialization)
}

val projectMinSdk: String by project
val projectCompileSdk: String by project

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

configure<LibraryExtension> {
    namespace = "net.devemperor.dictate.core"
    compileSdk = projectCompileSdk.toInt()

    defaultConfig {
        minSdk = projectMinSdk.toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("beta") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    // Public API surface (OkHttp/serialization types appear in signatures) -> api, so :app and :wear
    // see them transitively.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.okhttp.mockwebserver)
    // Kept alongside Kotest (both run on the JUnit 5 platform) so kotlin.test-based tests keep compiling.
    testImplementation(libs.kotlin.test.junit5)
}
