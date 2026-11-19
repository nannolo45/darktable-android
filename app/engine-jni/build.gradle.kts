// SPDX-License-Identifier: GPL-3.0-or-later
// JNI layer over engine/include/dtbridge.h, the engine unpacker and the engine thread.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// directory holding engine-<abi>/ trees unpacked from the engine release files
val engineDir: String = providers.gradleProperty("dta.engineDir").orNull
    ?: error("set -Pdta.engineDir=<dir with engine-arm64-v8a/ and engine-x86_64/>")
val abis: List<String> = (providers.gradleProperty("dta.abis").orNull ?: "arm64-v8a,x86_64").split(",")

android {
    namespace = "app.dtandroid.engine"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DDTA_ENGINE_DIR=$engineDir",
                    "-DDTB_INCLUDE_DIR=${rootDir.parentFile}/engine/include",
                    "-DANDROID_STL=none",
                )
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
