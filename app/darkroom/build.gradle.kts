// SPDX-License-Identifier: GPL-3.0-or-later
// The darkroom screen (ui-spec.md sections 1 to 4) for phone and tablet,
// generated from the control schema, on the DarktableEngine interface only.
// Screen code takes colours and sizes from ui-theme and icons from ui-icons;
// tools/app/check-screen-rules.py fails the build otherwise.
// Screenshot tests run on the JVM (Robolectric native graphics + Roborazzi).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Screenshot tests need Robolectric's native graphics and Android runtime jars,
// which Maven Central serves from a CDN host the build workspace cannot reach.
// -Pdta.screenshots=false leaves them out (local runs); CI always includes them.
val screenshots = providers.gradleProperty("dta.screenshots").orNull != "false"

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.dtandroid.darkroom"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (screenshots) sourceSets.getByName("test").kotlin.srcDir("src/screenshotTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            // write screenshots instead of comparing: they are looked at, not used as golden files
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.systemProperty("dta.screenshots", layout.buildDirectory.dir("outputs/screenshots").get().asFile.absolutePath)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine-api"))
    api(project(":schema"))
    api(project(":ui-theme"))
    api(project(":ui-icons"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(testFixtures(project(":engine-api")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    if (screenshots) {
        testImplementation("org.robolectric:robolectric:4.16")
        testImplementation("androidx.compose.ui:ui-test-junit4")
        testImplementation("androidx.compose.ui:ui-test-manifest")
        testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
        testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    }
}
