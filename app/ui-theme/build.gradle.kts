// SPDX-License-Identifier: GPL-3.0-or-later
// Theme tokens (PLAN.md 3.6): tokens.json in this folder is the one data file
// for colours and sizes. It is packed as a Java resource
// (dtandroid/theme/tokens.json) and read at start; debug builds may override
// it with a theme.json in the app's folder (see the app module).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class PackTokens : DefaultTask() {
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:InputFile abstract val altTokens: RegularFileProperty
    @get:InputFile abstract val modernTokens: RegularFileProperty
    @get:InputFile abstract val l1Tokens: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val out = outputDir.get().asFile.resolve("dtandroid/theme").apply { deleteRecursively(); mkdirs() }
        tokens.get().asFile.copyTo(out.resolve("tokens.json"))
        // the alternative palette ships too: an example for trying a palette, and the theming screenshot test
        altTokens.get().asFile.copyTo(out.resolve("alt-tokens.json"))
        // the modern skin (brief PUI2a), loaded on top of tokens.json when the setting `interface` is `modern`
        modernTokens.get().asFile.copyTo(out.resolve("modern-tokens.json"))
        // the l1 skin (brief PUI3a), loaded on top of modern-tokens.json when the setting `interface` is `l1`
        l1Tokens.get().asFile.copyTo(out.resolve("l1-tokens.json"))
    }
}

val packTokens = tasks.register<PackTokens>("packTokens") {
    tokens.set(layout.projectDirectory.file("tokens.json"))
    altTokens.set(layout.projectDirectory.file("alt-tokens.json"))
    modernTokens.set(layout.projectDirectory.file("modern-tokens.json"))
    l1Tokens.set(layout.projectDirectory.file("l1-tokens.json"))
    outputDir.set(layout.buildDirectory.dir("generated/tokens"))
}

android {
    namespace = "app.dtandroid.ui.theme"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(packTokens, PackTokens::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2025.10.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-text")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
