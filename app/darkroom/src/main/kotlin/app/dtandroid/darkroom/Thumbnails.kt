// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.RenderedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Thumbnails kept between starts, per photo (brief P2b). */
interface ThumbnailStore {
    fun load(item: SeriesItem): RenderedImage?
    fun store(item: SeriesItem, image: RenderedImage)
}

/**
 * Thumbnails as small raw RGBA files in [dir], named after the photo. A thumbnail is used only
 * while it is newer than the photo's sidecar (an edit made elsewhere makes a new one).
 */
class FileThumbnailStore(private val dir: File) : ThumbnailStore {
    private fun file(item: SeriesItem) = File(dir, item.fileName + ".thumb")

    override fun load(item: SeriesItem): RenderedImage? {
        val f = file(item)
        if (!f.isFile) return null
        val xmp = File(item.xmpPath)
        if (xmp.isFile && xmp.lastModified() > f.lastModified()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val w = s.readInt()
                val h = s.readInt()
                require(w in 1..4096 && h in 1..4096)
                val px = ByteArray(w * h * 4)
                s.readFully(px)
                RenderedImage(w, h, px)
            }
        }.getOrNull()
    }

    override fun store(item: SeriesItem, image: RenderedImage) {
        dir.mkdirs()
        val f = file(item)
        // a temporary file of its own per write: two renders of one photo may store its thumbnail at once
        val tmp = runCatching { File.createTempFile(f.name, ".tmp", dir) }.getOrNull() ?: return
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(image.width); s.writeInt(image.height); s.write(image.rgba)
            }
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }
}

object Thumbnails {
    /** Box-filtered copy whose longer side is at most [maxSide]; the image itself when already small enough. */
    fun downscale(img: RenderedImage, maxSide: Int): RenderedImage {
        val longer = max(img.width, img.height)
        if (longer <= maxSide) return img
        val f = longer.toDouble() / maxSide
        val w = max(1, (img.width / f).toInt())
        val h = max(1, (img.height / f).toInt())
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val y0 = (y * f).toInt()
            val y1 = min(img.height, max(y0 + 1, ((y + 1) * f).toInt()))
            for (x in 0 until w) {
                val x0 = (x * f).toInt()
                val x1 = min(img.width, max(x0 + 1, ((x + 1) * f).toInt()))
                var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val i = (yy * img.width + xx) * 4
                    r += img.rgba[i].toInt() and 0xff
                    g += img.rgba[i + 1].toInt() and 0xff
                    b += img.rgba[i + 2].toInt() and 0xff
                    n++
                }
                val o = (y * w + x) * 4
                out[o] = (r / n).toByte(); out[o + 1] = (g / n).toByte(); out[o + 2] = (b / n).toByte(); out[o + 3] = -1
            }
        }
        return RenderedImage(w, h, out)
    }
}
