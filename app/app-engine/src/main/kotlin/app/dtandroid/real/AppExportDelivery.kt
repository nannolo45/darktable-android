// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.dtandroid.darkroom.Delivered
import app.dtandroid.darkroom.ExportDelivery
import app.dtandroid.darkroom.LplOffer
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.lpl.LplAgent
import kotlinx.coroutines.flow.Flow
import app.dtandroid.darkroom.ExportTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Brief P3g: the export screen's destinations on the phone. "this device": the shared Pictures/darktable folder
 * through MediaStore (no storage permission needed from Android 10 on, the app's minimum); "share to an app": a
 * copy in the app's cache, offered through a FileProvider, and the share sheet once the whole export is done.
 */
class AppExportDelivery(private val app: Application) : ExportDelivery {
    override val scratch: File = File(app.cacheDir, "export")
    private val shared = File(app.cacheDir, "shared")

    override suspend fun deliver(file: File, name: String, mime: String, target: ExportTarget, item: SeriesItem, progress: (Float) -> Unit): Delivered = withContext(Dispatchers.IO) {
        when (target) {
            ExportTarget.DEVICE -> Delivered(toPictures(file, name, mime).toString())
            ExportTarget.LPL -> {
                // brief P3i (R18): the photo's own server, its id and version; a server that does not answer keeps it for the next sync
                val (origin, id) = AppHost.lplPhotoOf(item) ?: throw IOException("this photo is not from LightPhotoLibrary")
                when (val r = AppHost.agent.sendExport(origin, id, item.version, name, file, progress)) {
                    is LplAgent.ExportOutcome.Sent -> Delivered(r.path)
                    LplAgent.ExportOutcome.Queued -> Delivered("waiting for the next sync", queued = true)
                }
            }
            ExportTarget.SHARE -> {
                shared.mkdirs()
                // files shared by an earlier export are dropped after an hour
                shared.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEEP_SHARED_MS }?.forEach { it.delete() }
                val out = File(shared, name)
                file.copyTo(out, overwrite = true)
                Delivered(FileProvider.getUriForFile(app, "${app.packageName}.exports", out).toString())
            }
        }
    }

    /** brief P3i: LightPhotoLibrary is on offer for a photo of a server whose last heartbeat answer says `exports` */
    override fun lplOffer(item: SeriesItem?): LplOffer {
        val (origin, _) = item?.let { AppHost.lplPhotoOf(it) } ?: return LplOffer.Unavailable("this photo did not come from LightPhotoLibrary")
        if (AppHost.servers.get(origin) == null) return LplOffer.Unavailable("its server is no longer in the list")
        return when (AppHost.agent.receivesExports(origin)) {
            true -> LplOffer.Available
            false -> LplOffer.Unavailable("this server cannot receive exports yet: update LightPhotoLibrary")
            null -> LplOffer.Unavailable("the server has not answered yet: try again in a moment")
        }
    }

    override val lplOfferChanges: Flow<Int> get() = AppHost.agent.capsChanged

    private fun toPictures(file: File, name: String, mime: String): Uri {
        val cr = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/darktable")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Pictures/darktable cannot be written")
        try {
            (cr.openOutputStream(uri, "w") ?: throw IOException("Pictures/darktable cannot be written")).use { o ->
                file.inputStream().use { it.copyTo(o) }
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }

    override fun finished(target: ExportTarget, mime: String, delivered: List<String>) {
        if (target != ExportTarget.SHARE || delivered.isEmpty()) return
        val uris = ArrayList(delivered.map { Uri.parse(it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, "share the export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val KEEP_SHARED_MS = 60 * 60 * 1000L
    }
}
