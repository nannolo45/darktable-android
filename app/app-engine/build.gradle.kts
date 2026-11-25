// SPDX-License-Identifier: GPL-3.0-or-later
// The app on the real engine (brief P2b): the darkroom of the interface preview, driven by
// upstream darktable's engine through the JNI layer over dtbridge.h. Photos come in and go out
// through Android's file picker, and (brief P2e) from and to LightPhotoLibrary through app/lpl.
// Since P2e this is the product: application id app.dtandroid, label "dt-android". One APK per ABI (flavors arm64 and
// x86_64), each carrying the engine tree of its ABI as assets/engine/engine.zip, packed and
// unpacked exactly as the G0 app does.
import com.android.ide.common.vectordrawable.Svg2Vector
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
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

/**
 * Brief P2f deliverable 8: darktable's spektrafilm data pack (film and paper profiles, the spectral table),
 * zipped into assets/spektrafilm/ with its licence and an id (SHA-256 of the zip, first 16 hex digits).
 * The app unpacks it into the folder darktable reads a hand-installed pack from
 * (<XDG_DATA_HOME>/darktable/spektrafilm, src/common/spektra_fetch.c), because the engine cannot fetch it
 * (curl is built without TLS). -Pdta.spektrafilmDir=<dir> names a folder holding darktable/spektrafilm/ (as
 * schema/extractor/fetch_spektrafilm_pack.py leaves it) and LICENSE; without it the app carries no pack.
 */
abstract class PackSpektrafilm : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val root = source.get().asFile
        val pack = File(root, "darktable/spektrafilm")
        val out = File(outputDir.get().asFile, "spektrafilm").apply { deleteRecursively(); mkdirs() }
        val files = pack.walkTopDown().filter { it.isFile }.map { it.relativeTo(pack).invariantSeparatorsPath }
            .filter { !it.startsWith("packs/") }.sorted().toList()
        require(files.isNotEmpty()) { "$pack holds no spektrafilm pack" }
        val zip = File(out, "spektrafilm.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (rel in files) {
                val e = ZipEntry(rel)
                e.time = 0L
                z.putNextEntry(e)
                File(pack, rel).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        File(root, "LICENSE").takeIf { it.isFile }?.copyTo(File(out, "LICENSE"), overwrite = true)
        val md = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { st ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = st.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        File(out, "spektrafilm.id").writeText(md.digest().joinToString("") { "%02x".format(it) }.take(16) + "\n")
    }
}

val spektrafilmDir: String? = providers.gradleProperty("dta.spektrafilmDir").orNull

val abiOfFlavor = mapOf("arm64" to "arm64-v8a", "x86_64" to "x86_64")

/**
 * The launcher icon from darktable's own application icon (brand/darktable-icon.svg, GPL-3.0-or-later,
 * copied from upstream). Same pattern as :lpl-shell:shellIcons: adaptive icon (foreground, ground colour
 * from the "background: #rrggbb" comment, monochrome layer for themed icons). The launcher icon is outside
 * rule 5 (no literal colours in screen code): the icon's colours are darktable's mark, written in the SVG only.
 */
abstract class AppIcons : DefaultTask() {
    @get:InputFile abstract val brandSvg: RegularFileProperty
    @get:OutputDirectory abstract val resDir: DirectoryProperty

    /** The 512-unit drawing as a 108 dp adaptive layer: 512 units = 72 dp (0.140625), centred ((108 - 72) / 2 = 18). */
    private fun layer(mono: Boolean): String {
        val out = ByteArrayOutputStream()
        val errors = Svg2Vector.parseSvgToXml(brandSvg.get().asFile.toPath(), out)
        // Svg2Vector reports every SVG filter (the icon's 25 Gaussian blurs) as an error and draws the shapes
        // without it: a known, accepted simplification (docs/experiments/app-icon-REPORT.md). Any other message fails.
        val other = errors.orEmpty().lines().filter { it.isNotBlank() && !it.contains("<filter> is not supported") && !it.contains("<feGaussianBlur> is not supported") }
        require(other.isEmpty()) { "darktable-icon.svg: $other" }
        val xml = out.toString(Charsets.UTF_8)
        require(xml.contains("<path")) { "darktable-icon.svg: no paths" }
        val start = xml.indexOf('>', xml.indexOf("<vector")) + 1
        var inner = xml.substring(start, xml.lastIndexOf("</vector>"))
        if (mono) {
            // themed icon: one colour, the launcher tints it. The black/near-black shapes are the drawing's
            // shadow and ground (the blades' own gradient paths lie over them), so they are left out: the
            // silhouette is the six blades and the hub. Gradients become a plain fill.
            inner = inner.replace(Regex("(?s)<path\\b(?:(?!/>).)*?android:fillColor=\"#(1a1a1a|000000)\"(?:(?!/>).)*?/>"), "")
                .replace(Regex("(?s)<aapt:attr name=\"android:(fillColor|strokeColor)\">.*?</aapt:attr>"), "")
                .replace(Regex("android:(fillColor|strokeColor)=\"#[0-9A-Fa-f]{6,8}\""), "")
                .replace("<path", "<path android:fillColor=\"#FFFFFFFF\"")
        }
        return """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from app/app-engine/brand/darktable-icon.svg by :app-engine:appIcons. Do not edit. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    xmlns:aapt="http://schemas.android.com/aapt"
            |    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
            |  <group android:translateX="18" android:translateY="18" android:scaleX="0.140625" android:scaleY="0.140625">
            |$inner
            |  </group>
            |</vector>
            |""".trimMargin()
    }

    @TaskAction
    fun generate() {
        val bg = Regex("background: (#[0-9A-Fa-f]{6})\\b").find(brandSvg.get().asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException("darktable-icon.svg: no 'background: #rrggbb' comment")
        val res = resDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        res.resolve("values").mkdirs()
        res.resolve("values/dt_brand.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from app/app-engine/brand/darktable-icon.svg by :app-engine:appIcons. Do not edit. -->
            |<resources>
            |  <color name="dt_brand_bg">$bg</color>
            |</resources>
            |""".trimMargin()
        )
        res.resolve("drawable").mkdirs()
        res.resolve("drawable/dt_launcher_foreground.xml").writeText(layer(mono = false))
        res.resolve("drawable/dt_launcher_monochrome.xml").writeText(layer(mono = true))
        res.resolve("mipmap-anydpi-v26").mkdirs()
        res.resolve("mipmap-anydpi-v26/ic_launcher.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated by :app-engine:appIcons. Do not edit. -->
            |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            |  <background android:drawable="@color/dt_brand_bg" />
            |  <foreground android:drawable="@drawable/dt_launcher_foreground" />
            |  <monochrome android:drawable="@drawable/dt_launcher_monochrome" />
            |</adaptive-icon>
            |""".trimMargin()
        )
    }
}

val appIcons = tasks.register<AppIcons>("appIcons") {
    brandSvg.set(layout.projectDirectory.file("brand/darktable-icon.svg"))
    resDir.set(layout.buildDirectory.dir("generated/app-icons/res"))
}

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
        variant.sources.res?.addGeneratedSourceDirectory(appIcons, AppIcons::resDir)
        val flavor = variant.productFlavors.first { it.first == "abi" }.second
        val abi = abiOfFlavor.getValue(flavor)
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<PackEngine>("packEngine$cap") {
            engineTree.set(File(engineDir, "engine-$abi"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, PackEngine::outputDir)
        if (spektrafilmDir != null) {
            val sf = tasks.register<PackSpektrafilm>("packSpektrafilm$cap") { source.set(File(spektrafilmDir)) }
            variant.sources.assets?.addGeneratedSourceDirectory(sf, PackSpektrafilm::outputDir)
        }
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
    // brief P5c: taps on the real screens (the same Compose test library lpl-shell's instrumented tests use)
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.named("preBuild") { dependsOn(":checkScreenRules") }
