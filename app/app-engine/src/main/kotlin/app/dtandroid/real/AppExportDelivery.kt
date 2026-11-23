// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.dtandroid.darkroom.ExportDelivery
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

    override suspend fun deliver(file: File, name: String, mime: String, target: ExportTarget): String = withContext(Dispatchers.IO) {
        when (target) {
            ExportTarget.DEVICE -> toPictures(file, name, mime).toString()
            ExportTarget.SHARE -> {
                shared.mkdirs()
                // files shared by an earlier export are dropped after an hour
                shared.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEEP_SHARED_MS }?.forEach { it.delete() }
                val out = File(shared, name)
                file.copyTo(out, overwrite = true)
                FileProvider.getUriForFile(app, "${app.packageName}.exports", out).toString()
            }
        }
    }

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
