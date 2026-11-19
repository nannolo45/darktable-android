// SPDX-License-Identifier: GPL-3.0-or-later
// G0 diagnostic app (brief G0): unpacks the engine, drives it through the
// bridge and measures speed. One APK per ABI (flavors arm64 and x86_64), each
// carrying the engine tree of its ABI as assets/engine/engine.zip.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val engineDir: String = providers.gradleProperty("dta.engineDir").orNull
    ?: error("set -Pdta.engineDir=<dir with engine-arm64-v8a/ and engine-x86_64/>")
val appVersionName: String = providers.gradleProperty("dta.versionName").orNull ?: "g0-local"

/** Zip one engine tree (without bin/: an app cannot run executables from its storage). */
abstract class PackEngine : DefaultTask() {
    @get:InputDirectory abstract val engineTree: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val root = engineTree.get().asFile
        val out = File(outputDir.get().asFile, "engine").apply { deleteRecursively(); mkdirs() }
        val zip = File(out, "engine.zip")
        val files = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { !it.startsWith("bin/") }
            .sorted().toList()
        require("lib/load-order.txt" in files && "lib/libdtbridge.so" in files) {
            "$root is not an engine tree with the bridge (lib/libdtbridge.so, lib/load-order.txt)"
        }
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (rel in files) {
                val e = ZipEntry(rel)
                e.time = 0L
                z.putNextEntry(e)
                File(root, rel).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        File(out, "engine.id").writeText(md.digest().joinToString("") { "%02x".format(it) }.take(16) + "\n")
    }
}

val abiOfFlavor = mapOf("arm64" to "arm64-v8a", "x86_64" to "x86_64")

android {
    namespace = "app.dtandroid"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "app.dtandroid"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("x86_64") { dimension = "abi"; ndk { abiFilters += "x86_64" } }
    }
    buildTypes {
        // debug only: signed with the debug key of the machine that builds it
        getByName("debug") { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    androidResources {
        // engine.zip is already compressed
        noCompress += "zip"
    }
    packaging {
        // compress the (unshrunk, debug) dex files: about 25 MB less to download
        dex { useLegacyPackaging = true }
        jniLibs {
            // the bridge is loaded from the unpacked engine tree, never from the APK's lib/
            excludes += "**/libdtbridge.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

androidComponents {
    onVariants { variant ->
        val flavor = variant.productFlavors.first { it.first == "abi" }.second
        val abi = abiOfFlavor.getValue(flavor)
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<PackEngine>("packEngine$cap") {
            engineTree.set(File(engineDir, "engine-$abi"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, PackEngine::outputDir)
    }
}

dependencies {
    implementation(project(":engine-jni"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
