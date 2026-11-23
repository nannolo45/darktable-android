// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3g: the export screen (mockup B3-Export) idle, running, a series and done, and the top bar's indicator
 * after leaving the screen, in the darktable and modern skins (the l1 top bar's indicator too), on the stand-in
 * engine. The running states are set as they would be ([Exports.showJob]); P3gExportTest runs real ones.
 */
abstract class P3gExportScreens : ScreenshotBase() {
    protected fun shot(name: String, skin: Skin, job: ExportJob?, scope: ExportScope = ExportScope.PHOTO, screen: Boolean = true) {
        show(tokens = DtTokens.load(skin)) {
            exports.set { it.copy(scope = scope) }
            exports.showJob(job)
            if (screen) exports.open()
        }
        capture("${layoutName()}-p3g-$name-${skin.key}")
    }

    private fun layoutName() = if (layout == DarkroomLayout.TABLET) "tablet" else "phone"

    protected fun indicator(skin: Skin) = shot("indicator", skin, seriesJob(), ExportScope.SERIES, screen = false)

    protected fun seriesJob() = ExportJob(ExportPhase.RUNNING, ExportTarget.DEVICE, total = 12, finished = 4, number = 5,
        fileName = "IMG_0005.RAF", step = "processing · module 12 of 23", photoFraction = 0.55)
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneExportScreenshots : P3gExportScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun idleDarktable() = shot("idle", Skin.DARKTABLE, null)
    @Test fun idleModern() = shot("idle", Skin.MODERN, null)
    @Test fun runningDarktable() = shot("running", Skin.DARKTABLE, ExportJob(ExportPhase.RUNNING, ExportTarget.DEVICE, total = 1,
        fileName = "IMG_0001.RAF", step = "processing · module 7 of 23", photoFraction = 0.42))
    @Test fun runningModern() = shot("running", Skin.MODERN, ExportJob(ExportPhase.RUNNING, ExportTarget.DEVICE, total = 1,
        fileName = "IMG_0001.RAF", step = "processing · module 7 of 23", photoFraction = 0.42))
    @Test fun seriesDarktable() = shot("series", Skin.DARKTABLE, seriesJob(), ExportScope.SERIES)
    @Test fun seriesModern() = shot("series", Skin.MODERN, seriesJob(), ExportScope.SERIES)
    @Test fun doneDarktable() = shot("done", Skin.DARKTABLE, doneJob())
    @Test fun doneModern() = shot("done", Skin.MODERN, doneJob())
    @Test fun indicatorDarktable() = indicator(Skin.DARKTABLE)
    @Test fun indicatorModern() = indicator(Skin.MODERN)
    @Test fun indicatorL1() = indicator(Skin.L1)

    private fun doneJob() = ExportJob(ExportPhase.DONE, ExportTarget.DEVICE, total = 1, finished = 1,
        message = "1 photo · exported to Pictures/darktable on this device")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletExportScreenshots : P3gExportScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun seriesDarktable() = shot("series", Skin.DARKTABLE, ExportJob(ExportPhase.RUNNING, ExportTarget.DEVICE, total = 12,
        finished = 4, number = 5, fileName = "IMG_0005.RAF", step = "processing · module 12 of 23", photoFraction = 0.55), ExportScope.SERIES)
    @Test fun indicatorModern() = indicator(Skin.MODERN)
}
