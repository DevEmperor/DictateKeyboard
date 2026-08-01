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

import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
}

val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project

// Wear OS 3 (API 30) is the floor: standard third-party IMEs only work reliably from here on,
// and Wear Compose targets it. The phone app's minSdk (26) is intentionally NOT reused.
val wearMinSdk = 30

// The Wear app resubmits to Play on its own cadence (e.g. a review-fix re-upload) and must not reuse
// a burned versionCode. Bump this for a Wear-only re-release WITHOUT touching the phone's
// projectVersionCode: it adds to the +100000 Wear band and tags the versionName as "-wN". 0 = in
// lockstep with the phone base (no suffix). See versionCode/versionName in defaultConfig.
val wearRevision = 3

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

configure<ApplicationExtension> {
    namespace = "net.devemperor.dictate.wear"
    compileSdk = projectCompileSdk.toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        // Same applicationId as the phone app so Play delivers this as the "Wear OS" APK of the
        // same listing. Play still requires a unique versionCode per APK across the form factors;
        // we keep the Wear codes in a separate high band (see versionCode below).
        applicationId = "net.devemperor.dictate"
        minSdk = wearMinSdk
        targetSdk = projectTargetSdk.toInt()
        // Offset the Wear versionCode into its own band so it never collides with the phone APK
        // in the same Play listing; [wearRevision] lets the watch re-release independently.
        versionCode = projectVersionCode.toInt() + 100_000 + wearRevision
        versionName = projectVersionName.substringBefore("-") +
            (if (wearRevision > 0) "-w$wearRevision" else "")
    }

    buildFeatures {
        compose = true
    }

    lint {
        // The watch settings screen hosts a ComponentActivity directly (no Fragments), so the
        // activity-result registration is safe; this check is a false positive without a Fragment dep.
        disable.add("InvalidFragmentVersionForActivityResult")
    }

    // Mirror the phone module's signing setup: credentials live in the untracked keystore.properties
    // at the repo root; absent file -> unsigned build, same as the phone app.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = if (keystorePropsFile.exists()) {
        Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
    } else {
        null
    }
    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            isMinifyEnabled = false
        }

        named("release") {
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Shared transcription/LLM core (provider client, presets, prompts) — same module the phone app uses.
    implementation(projects.lib.dictateCore)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Wear-specific Compose UI.
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // Data Layer: settings sync + tethered transcription with the phone.
    implementation(libs.play.services.wearable)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutines.play.services)
}
