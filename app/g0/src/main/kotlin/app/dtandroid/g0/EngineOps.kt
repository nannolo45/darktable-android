// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.g0

import android.graphics.Bitmap
import app.dtandroid.engine.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A failed bridge call: the DTB_E_* code and dtb_last_error(). */
class BridgeException(val code: Int, what: String) :
    Exception("$what: ${NativeBridge.codeName(code)} ${NativeBridge.lastError()}") {
    val cancelled: Boolean get() = code == NativeBridge.E_CANCELLED
}

fun check(code: Int, what: String) {
    if (code != NativeBridge.OK) throw BridgeException(code, what)
}

/** One switchable module instance from the history. */
data class ModuleRow(val op: String, val prio: Int, val name: String, val enabled: Boolean)

data class Timed<T>(val value: T, val ms: Double)

inline fun <T> timed(block: () -> T): Timed<T> {
    val t0 = System.nanoTime()
    val v = block()
    return Timed(v, (System.nanoTime() - t0) / 1e6)
}

/**
 * Engine-side operations of the diagnostic app. Every function here runs on
 * the engine thread (call it inside Engine.call / Engine.callBlocking).
 */
object EngineOps {
    const val PREVIEW_SIZE = 1024
    /** S3 condition 1: render a region with this margin and crop it */
    const val REGION_MARGIN = 16

    fun rgbaToBitmap(w: Int, h: Int, rgba: ByteBuffer): Bitmap {
        // ARGB_8888 is stored as R, G, B, A bytes: the bridge's RGBA order
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        rgba.rewind()
        bmp.copyPixelsFromBuffer(rgba)
        return bmp
    }

    fun preview(maxSize: Int = PREVIEW_SIZE): Bitmap {
        val wh = IntArray(2)
        val st = IntArray(1)
        val bytes = NativeBridge.renderPreview(maxSize, wh, st)
        if (bytes == null) throw BridgeException(if (st[0] != 0) st[0] else NativeBridge.E_ENGINE, "preview")
        return rgbaToBitmap(wh[0], wh[1], ByteBuffer.wrap(bytes))
    }

    fun fullSize(scale: Float = 1f): Pair<Int, Int> {
        val wh = IntArray(2)
        check(NativeBridge.renderSize(scale, wh), "render size")
        return wh[0] to wh[1]
    }

    /** Region of the image at 100 % (scale 1), centred, at most w x h. */
    data class Region(val x: Int, val y: Int, val w: Int, val h: Int)

    fun centredRegion(screenW: Int, screenH: Int): Region {
        val (fw, fh) = fullSize()
        val w = minOf(screenW, fw)
        val h = minOf(screenH, fh)
        return Region((fw - w) / 2, (fh - h) / 2, w, h)
    }

    /**
     * Render [r] at scale 1 with a [REGION_MARGIN] px margin on every side
     * (clipped to the image) and crop the margin away.
     */
    fun region(r: Region): Bitmap {
        val (fw, fh) = fullSize()
        val x0 = maxOf(0, r.x - REGION_MARGIN)
        val y0 = maxOf(0, r.y - REGION_MARGIN)
        val x1 = minOf(fw, r.x + r.w + REGION_MARGIN)
        val y1 = minOf(fh, r.y + r.h + REGION_MARGIN)
        val mw = x1 - x0
        val mh = y1 - y0
        val buf = ByteBuffer.allocateDirect(mw * mh * 4).order(ByteOrder.nativeOrder())
        check(NativeBridge.render(x0, y0, mw, mh, 1f, buf), "region render")
        val out = ByteBuffer.allocate(r.w * r.h * 4)
        val row = ByteArray(r.w * 4)
        for (j in 0 until r.h) {
            buf.position(((r.y - y0 + j) * mw + (r.x - x0)) * 4)
            buf.get(row)
            out.put(row)
        }
        return rgbaToBitmap(r.w, r.h, out)
    }

    /** Whole image at full size into [buf] (reused between calls). */
    fun fullRender(buf: ByteBuffer?): ByteBuffer {
        val (fw, fh) = fullSize()
        val need = fw * fh * 4
        val b = if (buf != null && buf.capacity() >= need) buf else ByteBuffer.allocateDirect(need)
        check(NativeBridge.render(0, 0, fw, fh, 1f, b), "full render")
        return b
    }

    fun modules(): JSONArray = JSONArray(NativeBridge.modulesJson() ?: throw BridgeException(NativeBridge.E_ENGINE, "modules"))

    fun history(): JSONObject = JSONObject(NativeBridge.historyJson() ?: throw BridgeException(NativeBridge.E_ENGINE, "history"))

    /** Current value of exposure's "exposure" parameter (first instance). */
    fun exposure(): Float {
        val m = modules()
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            if (o.getString("op") == "exposure" && o.getInt("priority") == 0) {
                return o.getJSONObject("params").optDouble("exposure", 0.0).toFloat()
            }
        }
        return 0f
    }

    fun setExposure(ev: Float) = check(NativeBridge.paramSetFloat("exposure", 0, "exposure", ev), "set exposure")

    /** Module instances that appear in the history, in history order, with their on/off state. */
    fun historyModules(): List<ModuleRow> {
        val m = modules()
        val enabled = HashMap<String, Boolean>()
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            enabled["${o.getString("op")}/${o.getInt("priority")}"] = o.getBoolean("enabled")
        }
        val items = history().getJSONArray("items")
        val seen = LinkedHashMap<String, ModuleRow>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val op = o.getString("op")
            val prio = o.getInt("priority")
            val key = "$op/$prio"
            if (key in seen || key !in enabled) continue
            val label = o.optString("instance_name", "").let { if (it.isBlank() || it == "0") op else "$op $it" }
            seen[key] = ModuleRow(op, prio, label, enabled.getValue(key))
        }
        return seen.values.toList()
    }
}
