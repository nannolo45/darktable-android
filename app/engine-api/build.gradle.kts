// SPDX-License-Identifier: GPL-3.0-or-later
// DarktableEngine (docs/design/engine-api.md sections 1 and 2), its JSON
// shapes, and FakeEngine. Pure Kotlin: no Android dependency, tests on the JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    // the engine contract tests live in test fixtures, so the JNI engine can run the same suite later
    id("java-test-fixtures")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testFixturesApi("junit:junit:4.13.2")
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// the tests read the repository's schema file directly
tasks.test {
    systemProperty("dta.schema", rootDir.resolve("../schema/ui-schema.json").absolutePath)
}
