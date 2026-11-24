// SPDX-License-Identifier: GPL-3.0-or-later
// The LightPhotoLibrary agent (brief P2e, PLAN.md 4.1): the HTTP client for LPL's agent calls, the hand-off
// link, the servers the user added, the working set on disk and the sync rules. Pure Kotlin: no Android
// classes, so all of it runs in JVM tests. HTTP through java.net.HttpURLConnection (no client library).
// The stub LPL server used by the JVM tests and the app's instrumented tests is in the test fixtures.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-test-fixtures")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// brief P3l: the names PLAN.md 4.5 does not share (docs/design/R11-excluded-names.json), packed as a resource
val packShared by tasks.registering(Sync::class) {
    from(rootDir.resolve("../docs/design/R11-excluded-names.json"))
    into(layout.buildDirectory.dir("generated/shared/dtandroid/lpl"))
}

sourceSets.main {
    resources.srcDir(packShared.map { layout.buildDirectory.dir("generated/shared").get() })
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    // the run against a real LPL server (RealServerRun) only happens when its address is given:
    //   -Pdta.lplUrl=http://127.0.0.1:18790 [-Pdta.lplPassword=...] [-Pdta.lplLog=<file>]
    for (p in listOf("dta.lplUrl", "dta.lplPassword", "dta.lplLog", "dta.lplRawId", "dta.r11Url")) {
        providers.gradleProperty(p).orNull?.let { systemProperty(p, it) }
    }
    outputs.upToDateWhen { !providers.gradleProperty("dta.lplUrl").isPresent && !providers.gradleProperty("dta.r11Url").isPresent }
}
