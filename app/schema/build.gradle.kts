// SPDX-License-Identifier: GPL-3.0-or-later
// Control schema loader (PLAN.md 3.3, docs/experiments/S5-control-schema.md):
// the control model, visibility and sensitivity rules, overrides and module
// groups. schema/ui-schema.json and schema/overrides/*.json are packed into
// the jar at build time straight from the repository, never copied by hand.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val repoSchema = rootDir.resolve("../schema")

val packSchema by tasks.registering(Sync::class) {
    description = "Pack schema/ui-schema.json and schema/overrides/*.json from the repository"
    from(repoSchema.resolve("ui-schema.json"))
    from(repoSchema.resolve("overrides")) {
        include("*.json")
        into("overrides")
    }
    into(layout.buildDirectory.dir("generated/schema/dtandroid/schema"))
    val overridesDir = repoSchema.resolve("overrides")
    inputs.dir(overridesDir)
    doLast {
        val names = overridesDir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }.sorted()
        destinationDir.resolve("overrides/index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(packSchema.map { layout.buildDirectory.dir("generated/schema").get() })
}

dependencies {
    api(project(":engine-api"))
    testImplementation("junit:junit:4.13.2")
}
