// SPDX-License-Identifier: GPL-3.0-or-later
// The interface preview (brief P2a): the darkroom on the fake engine's sample series; no working set
// and no LightPhotoLibrary connection. Application id app.dtandroid.preview since P2e (brief P2e item 8:
// app.dtandroid is now the real-engine app, module app-engine), so both install side by side.
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
        applicationId = "app.dtandroid.preview"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = appVersionName
        // no native code yet; the preview is published for the phones the project targets
        ndk { abiFilters += "arm64-v8a" }
    }
    // Signing: with -Pdta.keystore=<absolute file> (the owner's key, handed to CI as a secret) every build
    // carries the same signature, so a newer build installs over an older one. The file has the form of an
    // Android debug keystore (alias and passwords below are that form's fixed values, not secrets; the file
    // is the secret and is never in the repository). Without the property: the building machine's debug key.
    val ownerKeystore: String? = providers.gradleProperty("dta.keystore").orNull
    signingConfigs {
        if (ownerKeystore != null) {
            create("owner") {
                storeFile = file(ownerKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        // debug only for now
        getByName("debug") {
            isMinifyEnabled = false
            if (ownerKeystore != null) signingConfig = signingConfigs.getByName("owner")
        }
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
