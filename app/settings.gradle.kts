// SPDX-License-Identifier: GPL-3.0-or-later
// Android project (PLAN.md 3.5).
//   engine-api  DarktableEngine interface, JSON shapes, FakeEngine (pure Kotlin)
//   schema      control schema loader, visibility rules, overrides, module groups (pure Kotlin)
//   ui-theme    theme tokens (ui-theme/tokens.json) for Compose
//   ui-icons    icon registry; drawings in ui-icons/svg/, converted at build time
//   darkroom    the darkroom screen (phone and tablet)
//   app         the interface preview on the fake engine (brief P2a), application id app.dtandroid.preview
//   lpl-shell   the LightPhotoLibrary shell, a second small app (app.dtandroid.lplshell, brief P2c);
//               no darktable code: only ui-theme and ui-icons
//   lpl         the LightPhotoLibrary agent: client, hand-off link, servers, working set, sync (brief P2e; pure Kotlin)
//   app-engine  the app on the real engine (brief P2b), since P2e the product: application id app.dtandroid
//   (the interface preview, module app, moved to app.dtandroid.preview in P2e)
// engine-jni, g0 (brief G0) and app-engine (brief P2b) need the native engine archives and are
// only included when -Pdta.engineDir is given, so the interface builds without them.
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
include(":engine-api", ":schema", ":ui-theme", ":ui-icons", ":darkroom", ":app", ":lpl-shell", ":lpl")
if (providers.gradleProperty("dta.engineDir").isPresent) {
    include(":engine-jni", ":g0", ":app-engine")
}
