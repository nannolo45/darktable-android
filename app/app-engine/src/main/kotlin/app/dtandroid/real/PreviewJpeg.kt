// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.graphics.Bitmap
import app.dtandroid.engine.api.RenderedImage
import java.io.File

/** Brief P2f: a rendered picture (RGBA8, the engine's order) as a JPEG file, with Android's encoder. */
object PreviewJpeg {
    fun write(img: RenderedImage, f: File, quality: Int) {
        val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        try {
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(img.rgba))
            f.outputStream().buffered().use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw java.io.IOException("JPEG encoding failed")
            }
        } finally {
            bmp.recycle()
        }
    }
}
