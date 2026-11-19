// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

// CLAUDE.md rule 5 / PLAN.md 3.6: screen code (darkroom, app, lpl-shell, app-engine) may not contain literal
// colours or sizes or draw icons; the build of those modules fails otherwise.
val checkScreenRules by tasks.registering(Exec::class) {
    description = "Fail on literal colours, sizes or icon drawings in screen code (tools/app/check-screen-rules.py)"
    group = "verification"
    val script = rootDir.resolve("../tools/app/check-screen-rules.py")
    val report = layout.buildDirectory.file("check-screen-rules.txt")
    inputs.file(script)
    inputs.dir("darkroom/src/main")
    inputs.dir("app/src/main")
    inputs.dir("lpl-shell/src/main")
    inputs.dir("app-engine/src/main")
    outputs.file(report)
    commandLine("python3", script.path)
    doFirst { report.get().asFile.parentFile.mkdirs() }
    doLast { report.get().asFile.writeText("passed\n") }
}

val checkScreenRulesSelfTest by tasks.registering(Exec::class) {
    description = "The rule check finds every violation in its fixtures and none in clean code"
    group = "verification"
    commandLine("python3", rootDir.resolve("../tools/app/check-screen-rules.py").path, "--self-test")
}
