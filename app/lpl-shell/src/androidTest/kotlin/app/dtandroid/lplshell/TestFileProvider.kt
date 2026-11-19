// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Test APK only: serves `content://app.dtandroid.lplshell.test.files/<name>`
 * as a small made-up file of [SIZE] bytes, the way a file picker's provider
 * would. Used to answer the file chooser in [ShellTest].
 */
class TestFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: "x"
        val f = File(context!!.cacheDir, "chooser-$name")
        if (!f.exists()) f.writeBytes(ByteArray(SIZE) { (it % 251).toByte() })
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) SIZE.toLong() else if (it == OpenableColumns.DISPLAY_NAME) uri.lastPathSegment else null }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "app.dtandroid.lplshell.test.files"
        const val SIZE = 1234
    }
}
