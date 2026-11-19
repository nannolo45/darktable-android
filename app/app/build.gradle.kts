// SPDX-License-Identifier: GPL-3.0-or-later
// The app shell (PLAN.md 3.5), application id app.dtandroid. Brief P2a: it
// opens the darkroom on the fake engine's sample series; no working set and
// no LightPhotoLibrary connection yet. Debug builds are the interface preview.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName: String = providers.gradleProperty("dta.versionName").orNull ?: "preview-local"

android {
    namespace = "app.dtandroid"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.dtandroid"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = appVersionName
        // no native code yet; the preview is published for the phones the project targets
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        // debug only for now: signed with the debug key of the machine that builds it (generated in CI)
        getByName("debug") { isMinifyEnabled = false }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        dex { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":darkroom"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.named("preBuild") { dependsOn(":checkScreenRules") }
