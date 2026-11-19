// SPDX-License-Identifier: GPL-3.0-or-later
// Icon registry (PLAN.md 3.6): screens ask for icons by meaning (DtIcon.Power).
// The drawings are the SVG files in svg/; at build time each becomes an
// Android vector drawable (with Android's own converter, Svg2Vector) and the
// DtIcon enum is generated from the file names.
import com.android.ide.common.vectordrawable.Svg2Vector
import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class ConvertIcons : DefaultTask() {
    @get:InputDirectory abstract val svgDir: DirectoryProperty
    @get:OutputDirectory abstract val resDir: DirectoryProperty
    @get:OutputDirectory abstract val srcDir: DirectoryProperty

    @TaskAction
    fun convert() {
        val drawables = resDir.get().asFile.resolve("drawable").apply { parentFile.deleteRecursively(); mkdirs() }
        val src = srcDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val svgs = svgDir.get().asFile.listFiles { f -> f.name.endsWith(".svg") }!!.sortedBy { it.name }
        require(svgs.isNotEmpty()) { "no icons in ${svgDir.get()}" }
        val entries = ArrayList<String>()
        for (svg in svgs) {
            val key = svg.nameWithoutExtension
            require(Regex("[a-z][A-Za-z0-9]*").matches(key)) { "icon file name $key must be lowerCamelCase" }
            val resName = "dt_icon_" + key.replace(Regex("([A-Z])"), "_$1").lowercase()
            val out = ByteArrayOutputStream()
            val errors = Svg2Vector.parseSvgToXml(svg.toPath(), out)
            require(errors.isNullOrEmpty()) { "${svg.name}: $errors" }
            drawables.resolve("$resName.xml").writeBytes(out.toByteArray())
            entries += "    ${key.replaceFirstChar { it.uppercase() }}(\"$key\", R.drawable.$resName)"
        }
        src.resolve("DtIcon.kt").writeText(
            """
            |// Generated from app/ui-icons/svg by the convertIcons task. Do not edit.
            |package app.dtandroid.ui.icons
            |
            |/** Icons by meaning; the drawing of each is svg/<key>.svg. */
            |enum class DtIcon(val key: String, val drawable: Int) {
            |${entries.joinToString(",\n")};
            |
            |    companion object {
            |        fun byKey(key: String): DtIcon? = entries.firstOrNull { it.key == key }
            |    }
            |}
            |""".trimMargin()
        )
    }
}

val convertIcons = tasks.register<ConvertIcons>("convertIcons") {
    svgDir.set(layout.projectDirectory.dir("svg"))
    resDir.set(layout.buildDirectory.dir("generated/icons/res"))
    srcDir.set(layout.buildDirectory.dir("generated/icons/kotlin"))
}

android {
    namespace = "app.dtandroid.ui.icons"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(convertIcons, ConvertIcons::resDir)
        variant.sources.java?.addGeneratedSourceDirectory(convertIcons, ConvertIcons::srcDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2025.10.01"))
    api("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
}
