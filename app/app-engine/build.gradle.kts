// SPDX-License-Identifier: GPL-3.0-or-later
// The app on the real engine (brief P2b): the darkroom of the interface preview, driven by
// upstream darktable's engine through the JNI layer over dtbridge.h. Photos come in and go out
// through Android's file picker, and (brief P2e) from and to LightPhotoLibrary through app/lpl.
// Since P2e this is the product: application id app.dtandroid, label "dt-android". One APK per ABI (flavors arm64 and
// x86_64), each carrying the engine tree of its ABI as assets/engine/engine.zip, packed and
// unpacked exactly as the G0 app does.
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
val appVersionName: String = providers.gradleProperty("dta.versionName").orNull ?: "engine-local"

/** Zip one engine tree (without bin/: an app cannot run executables from its storage). Same as app/g0. */
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
    namespace = "app.dtandroid.real"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        // brief P2e item 8 (reviewer's decision): the product's id. Until P2e it was app.dtandroid.real and
        // app.dtandroid was the interface preview (now app.dtandroid.preview). versionCode 2 > the old
        // preview's 1, so this installs over a left-over preview as an update (same owner key).
        applicationId = "app.dtandroid"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("x86_64") { dimension = "abi"; ndk { abiFilters += "x86_64" } }
    }
    // Signing as app/app (main 0071d6a): -Pdta.keystore=<absolute path of a keystore in debug-keystore form>
    // signs the debug build, and with it the instrumented test APK, with the owner's key (CI writes the
    // secret SIGNING_KEYSTORE_B64 there; the key is never in the repository). Without the property: the
    // building machine's debug key.
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
        // debug only
        getByName("debug") {
            isMinifyEnabled = false
            if (ownerKeystore != null) signingConfig = signingConfigs.getByName("owner")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // engine.zip is already compressed
        noCompress += "zip"
    }
    packaging {
        // compress the (unshrunk, debug) dex files
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
    implementation(project(":darkroom"))
    implementation(project(":lpl"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    androidTestImplementation(testFixtures(project(":engine-api")))
    androidTestImplementation(testFixtures(project(":lpl")))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.named("preBuild") { dependsOn(":checkScreenRules") }
