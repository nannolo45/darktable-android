// SPDX-License-Identifier: GPL-3.0-or-later
// Android project (PLAN.md 3.5). For now: the JNI layer over dtbridge.h and
// the G0 diagnostic app (brief G0).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "dt-android"
include(":engine-jni", ":g0")
