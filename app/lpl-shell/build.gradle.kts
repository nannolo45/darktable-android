// SPDX-License-Identifier: GPL-3.0-or-later
// LightPhotoLibrary shell (brief P2c, PLAN.md 3.5): a second small app,
// application id app.dtandroid.lplshell. It shows one LightPhotoLibrary
// server's web interface full screen in the platform web view and hands the
// "Edit on this phone" link to the darktable app. No darktable code, no engine,
// no native code of its own; screens use ui-theme tokens and ui-icons only.
//
// The launcher icon is LightPhotoLibrary's own mark (brand/lpl-icon.svg, the one place its colours
// are written); the shortcut icon comes from ui-icons' SVG files and ui-theme's tokens.json. Both are
// generated here, so nothing is drawn or coloured by hand in the resources.
import com.android.ide.common.vectordrawable.Svg2Vector
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val shellVersionName: String = providers.gradleProperty("dta.shellVersionName").orNull ?: "0.1-local"
val shellVersionCode: Int = providers.gradleProperty("dta.shellVersionCode").orNull?.toInt() ?: 1

/**
 * The launcher icon from LightPhotoLibrary's own mark (brand/lpl-icon.svg, its colours included), and the
 * shortcut icon from ui-icons/svg, coloured with ui-theme tokens.
 */
abstract class ShellIcons : DefaultTask() {
    @get:InputDirectory abstract val svgDir: DirectoryProperty
    @get:InputFile abstract val brandSvg: RegularFileProperty
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:OutputDirectory abstract val resDir: DirectoryProperty

    private fun color(t: Map<*, *>, name: String): String {
        val v = t[name] as? String ?: throw GradleException("token $name missing in tokens.json")
        require(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?").matches(v)) { "token $name: $v" }
        return v
    }

    /** The icon's paths in a 108 x 108 adaptive-icon layer, inside the 66 dp safe zone, in [stroke]. */
    private fun foreground(svgName: String, stroke: String): String {
        val out = ByteArrayOutputStream()
        val errors = Svg2Vector.parseSvgToXml(svgDir.get().asFile.resolve(svgName).toPath(), out)
        require(errors.isNullOrEmpty()) { "$svgName: $errors" }
        val xml = out.toString(Charsets.UTF_8)
        val start = xml.indexOf('>', xml.indexOf("<vector")) + 1
        val inner = xml.substring(start, xml.lastIndexOf("</vector>"))
            .replace(Regex("\"#(FF)?000000\""), "\"$stroke\"")
        // 24 -> 48 units, centred: (108 - 48) / 2 = 30
        return """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from app/ui-icons/svg/$svgName by :lpl-shell:shellIcons. Do not edit. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
            |  <group android:translateX="30" android:translateY="30" android:scaleX="2" android:scaleY="2">
            |$inner
            |  </group>
            |</vector>
            |""".trimMargin()
    }

    /**
     * LightPhotoLibrary's mark as an adaptive-icon layer. The drawing's 512 box is the 72 dp a launcher
     * shows, centred in the 108 dp layer, which keeps the drawing inside the 66 dp safe zone.
     * [mono]: every shape in one colour, for themed icons (the launcher tints it).
     */
    private fun brandForeground(mono: Boolean): String {
        val out = ByteArrayOutputStream()
        val errors = Svg2Vector.parseSvgToXml(brandSvg.get().asFile.toPath(), out)
        require(errors.isNullOrEmpty()) { "lpl-icon.svg: $errors" }
        val xml = out.toString(Charsets.UTF_8)
        val start = xml.indexOf('>', xml.indexOf("<vector")) + 1
        var inner = xml.substring(start, xml.lastIndexOf("</vector>"))
        if (mono) inner = inner.replace(Regex("android:fillColor=\"#[0-9A-Fa-f]{6,8}\""), "android:fillColor=\"#FFFFFFFF\"")
        // 512 units -> 72 dp: 72 / 512 = 0.140625; centred: (108 - 72) / 2 = 18
        return """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from app/lpl-shell/brand/lpl-icon.svg by :lpl-shell:shellIcons. Do not edit. -->
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
            |  <group android:translateX="18" android:translateY="18" android:scaleX="0.140625" android:scaleY="0.140625">
            |$inner
            |  </group>
            |</vector>
            |""".trimMargin()
    }

    /** The mark's ground colour, from the "background: #rrggbb" comment in brand/lpl-icon.svg. */
    private fun brandBackground(): String =
        Regex("background: (#[0-9A-Fa-f]{6})\\b").find(brandSvg.get().asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException("lpl-icon.svg: no 'background: #rrggbb' comment")

    private fun adaptive(bg: String, fg: String, mono: String = fg) = """<?xml version="1.0" encoding="utf-8"?>
        |<!-- Generated by :lpl-shell:shellIcons. Do not edit. -->
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |  <background android:drawable="@color/$bg" />
        |  <foreground android:drawable="@drawable/$fg" />
        |  <monochrome android:drawable="@drawable/$mono" />
        |</adaptive-icon>
        |""".trimMargin()

    @TaskAction
    fun generate() {
        val t = JsonSlurper().parse(tokens.get().asFile) as Map<*, *>
        val res = resDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val bg = color(t, "color.bg")
        val fg = color(t, "color.textStrong")
        res.resolve("values").mkdirs()
        res.resolve("values/lpl_tokens.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from app/ui-theme/tokens.json by :lpl-shell:shellIcons. Do not edit. -->
            |<resources>
            |  <color name="lpl_token_bg">$bg</color>
            |  <color name="lpl_token_text_strong">$fg</color>
            |  <color name="lpl_brand_bg">${brandBackground()}</color>
            |</resources>
            |""".trimMargin()
        )
        res.resolve("drawable").mkdirs()
        res.resolve("drawable/lpl_launcher_foreground.xml").writeText(brandForeground(mono = false))
        res.resolve("drawable/lpl_launcher_monochrome.xml").writeText(brandForeground(mono = true))
        res.resolve("drawable/lpl_shortcut_edit_foreground.xml").writeText(foreground("edit.svg", fg))
        res.resolve("mipmap-anydpi-v26").mkdirs()
        res.resolve("mipmap-anydpi-v26/ic_launcher.xml").writeText(adaptive("lpl_brand_bg", "lpl_launcher_foreground", "lpl_launcher_monochrome"))
        res.resolve("drawable-anydpi-v26").mkdirs()
        res.resolve("drawable-anydpi-v26/lpl_shortcut_edit.xml").writeText(adaptive("lpl_token_bg", "lpl_shortcut_edit_foreground"))
    }
}

val shellIcons = tasks.register<ShellIcons>("shellIcons") {
    svgDir.set(rootProject.layout.projectDirectory.dir("ui-icons/svg"))
    brandSvg.set(layout.projectDirectory.file("brand/lpl-icon.svg"))
    tokens.set(rootProject.layout.projectDirectory.file("ui-theme/tokens.json"))
    resDir.set(layout.buildDirectory.dir("generated/shell-icons/res"))
}

android {
    namespace = "app.dtandroid.lplshell"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.dtandroid.lplshell"
        minSdk = 29
        targetSdk = 34
        versionCode = shellVersionCode
        versionName = shellVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // no abiFilters: no native code of its own, one universal APK
    }
    buildTypes {
        // debug only for now: signed with the debug key of the machine that builds it (generated in CI)
        getByName("debug") { isMinifyEnabled = false }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        dex { useLegacyPackaging = true }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(shellIcons, ShellIcons::resDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":ui-theme"))
    implementation(project(":ui-icons"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(":checkScreenRules") }
