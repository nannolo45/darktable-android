// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.lpl.VersionFiles
import app.dtandroid.lpl.VersionFolder
import java.io.File

/**
 * Brief P3f (docs/design/P3f-versions.md): a version of the photo is another sidecar, `IMG_01_02.RAF.xmp` beside
 * `IMG_01.RAF.xmp`. [SeriesItem.version] says which one the item stands for; its [SeriesItem.xmpPath] is that file.
 */
fun SeriesItem.atVersion(n: Int): SeriesItem {
    val raw = File(rawPath)
    return copy(version = n, xmpPath = VersionFiles.file(raw.parentFile ?: File("."), raw.name, n).path)
}

/** The folder logic for the versions of [item]'s photo. */
fun versionFolderOf(item: SeriesItem): VersionFolder {
    val raw = File(item.rawPath)
    return VersionFolder(raw.parentFile ?: File("."), raw.name)
}

/** Where a version's changes stand with LightPhotoLibrary, for the dot on the sheet's row. */
enum class VersionSync(val word: String) {
    /** LPL has this version as it is on the phone */
    SYNCED("synced"),
    /** changed or new on the phone: it goes to LPL at the next sync */
    WAITING("waiting to sync"),
    /** not sent: the photo is not from LPL, or LPL's server does not know versions */
    LOCAL("on this phone only"),
}

/** One row of the versions sheet. */
data class VersionUiRow(
    val n: Int,
    val name: String,
    val edited: Boolean,
    val sync: VersionSync,
    val cover: Boolean,
    val current: Boolean,
    /** the version's last picture seen in this run (from the darkroom); null = not seen yet */
    val thumb: RenderedImage?,
)

/** Strip versions: one version of a photo as the filmstrip shows it when the photo's versions are expanded. */
data class StripVersion(
    val n: Int,
    val name: String,
    /** the version's last picture seen in this run; null = not opened yet (the tile shows its number) */
    val thumb: RenderedImage?,
)

/**
 * What the app gives the darkroom for versions beyond files: whether LightPhotoLibrary lets a version be made, where
 * each version stands in the sync, and what to do on LPL when a version is deleted or made the cover. The defaults are
 * a photo in the app's own folder: everything local.
 */
interface VersionHost {
    /** A version may be made of this photo now (false: LPL's server does not know versions and is connected). */
    fun canMake(item: SeriesItem): Boolean = true

    fun syncOf(item: SeriesItem, n: Int): VersionSync = VersionSync.LOCAL

    /** A version file was written for [n]. */
    fun made(item: SeriesItem, n: Int) {}

    /**
     * Delete version [n] (its file and, later, LPL's copy). True = done here, false = not possible now (the photo is
     * open on it), null = not an LPL photo: the darkroom deletes the file itself.
     */
    suspend fun delete(item: SeriesItem, n: Int): Boolean? = null

    /** The cover version LPL shows for the photo (0 when unknown or not an LPL photo). */
    fun cover(item: SeriesItem): Int = 0

    /** "Set as cover" is offered (LPL photo on a server with versions). */
    fun canSetCover(item: SeriesItem): Boolean = false

    /** Ask LPL to make [n] the cover; an error text for the user, null when done. */
    suspend fun setCover(item: SeriesItem, n: Int): String? = "this photo is not in LightPhotoLibrary"
}
