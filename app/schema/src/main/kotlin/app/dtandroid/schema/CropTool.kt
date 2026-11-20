// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Brief P3a: crop's frame on the photo, as desktop's crop module handles it (`src/iop/crop.c` at the pin).
 *
 * The frame is kept as desktop keeps it while the module has the focus: [Clip] is `g->clip_x/y/w/h`, fractions of
 * crop's input (the uncropped picture), stored as `float` as there. Each function is a transcription of one
 * function of crop.c, in float where crop.c computes in float and in double where it computes in double, so the
 * results equal desktop's (the JVM tests compare them with values computed by crop.c's own code,
 * `tools/p3a-crop-reference.py`):
 *
 * - [aspectApply]: `_aspect_apply` (corners move two sides, edges one side and the other dimension around its
 *   centre, then the frame is pulled back inside the image);
 * - [Drag]: `button_pressed` (which part was grabbed, the handle offsets) and `mouse_moved` with the first button
 *   down (resize from the grabbed sides with the minimum size, move the whole frame inside the image, then
 *   `_aspect_apply` with the grab);
 * - [desktopGrab]: `_gui_get_grab`, desktop's hit test (30 px borders inside the frame);
 * - [commit]: `_commit_box`: the frame in pixels of crop's input, rounded to whole multiples of the ratio when it
 *   is exact (`_reduce_aligners`), clamped, as the parameters `cx, cy, cw, ch` (left, top, right, bottom).
 *
 * The maximum frame (`clip_max_*`) is the whole picture: it is smaller on desktop only when a module after crop
 * changes the geometry (framing); the app does not open the tool then. Shift and ctrl (centre-locked resize,
 * move along one axis) have no touch equivalent and are left out.
 *
 * [hitTest] is the app's own: desktop's grab regions sized for a finger (see its comment).
 */
object CropTool {
    /** crop.c `MIN_CROP_SIZE`: the smallest width and height, as a fraction of the picture */
    const val MIN_CROP_SIZE = 0.01f

    // crop.c `_grab_region_t`
    const val GRAB_CENTER = 0
    const val GRAB_LEFT = 1
    const val GRAB_TOP = 2
    const val GRAB_RIGHT = 4
    const val GRAB_BOTTOM = 8
    const val GRAB_TOP_LEFT = GRAB_TOP or GRAB_LEFT
    const val GRAB_TOP_RIGHT = GRAB_TOP or GRAB_RIGHT
    const val GRAB_BOTTOM_RIGHT = GRAB_BOTTOM or GRAB_RIGHT
    const val GRAB_BOTTOM_LEFT = GRAB_BOTTOM or GRAB_LEFT
    const val GRAB_HORIZONTAL = GRAB_LEFT or GRAB_RIGHT
    const val GRAB_VERTICAL = GRAB_TOP or GRAB_BOTTOM
    const val GRAB_ALL = GRAB_LEFT or GRAB_TOP or GRAB_RIGHT or GRAB_BOTTOM
    const val GRAB_NONE = 16

    /** The frame: left, top, width, height as fractions of the uncropped picture (desktop's `g->clip_*`). */
    data class Clip(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right: Float get() = x + w
        val bottom: Float get() = y + h

        /** As the parameters: left, top, right, bottom. */
        fun toFrame() = CropAspect.Frame(x.toDouble(), y.toDouble(), (x + w).toDouble(), (y + h).toDouble())

        companion object {
            val FULL = Clip(0f, 0f, 1f, 1f)

            /** `gui_focus(in)`: the frame from the parameters (left, top, right, bottom). */
            fun fromParams(cx: Float, cy: Float, cw: Float, ch: Float): Clip {
                val x = clampf(cx, 0f, 1f - MIN_CROP_SIZE)
                val y = clampf(cy, 0f, 1f - MIN_CROP_SIZE)
                return Clip(x, y, clampf(cw - cx, MIN_CROP_SIZE, 1f - x), clampf(ch - cy, MIN_CROP_SIZE, 1f - y))
            }
        }
    }

    /** darktable's `CLAMPF` (src/common/math.h) */
    private fun clampf(a: Float, mn: Float, mx: Float): Float = if (a >= mn) (if (a <= mx) a else mx) else mn

    /** darktable's `CLIP` */
    private fun clip(x: Double): Double = if (x >= 0) (if (x <= 1) x else 1.0) else 0.0

    /** GLib's `CLAMP` */
    private fun clampD(x: Double, lo: Double, hi: Double): Double = if (x > hi) hi else if (x < lo) lo else x

    /**
     * `_aspect_ratio_get` (a float there): the ratio the frame is held to, from the parameters `ratio_d`,
     * `ratio_n`; 0 or less is freehand. [inW] x [inH] is crop's input.
     */
    fun aspect(spec: AspectSpec, ratioD: Int, ratioN: Int, inW: Double, inH: Double): Float =
        CropAspect.aspect(spec, ratioD, ratioN, inW, inH).toFloat()

    /** `_aspect_apply`: the frame reshaped to [aspect0] from the [grab]bed sides, for crop's input [iwd] x [iht]. */
    fun aspectApply(g: Clip, aspect0: Float, grab: Int, iwd: Double, iht: Double): Clip {
        var aspect = aspect0.toDouble()
        if (iwd < iht && aspect != 0.0) aspect = 1.0 / aspect
        if (aspect <= 0.0) return g
        val maxX = 0.0
        val maxY = 0.0
        val maxW = 1.0
        val maxH = 1.0
        var clipX = max(iwd * g.x / iwd, 0.0)
        var clipY = max(iht * g.y / iht, 0.0)
        var clipW = min(iwd * g.w / iwd, 1.0)
        var clipH = min(iht * g.h / iht, 1.0)
        val targetH = iwd * g.w / (iht * aspect)
        val targetW = iht * g.h * aspect / iwd
        when {
            grab == GRAB_TOP_LEFT -> {
                clipX = clipX + clipW - (targetW + clipW) * .5
                clipY = clipY + clipH - (targetH + clipH) * .5
                clipW = (targetW + clipW) * .5
                clipH = (targetH + clipH) * .5
            }
            grab == GRAB_TOP_RIGHT -> {
                clipY = clipY + clipH - (targetH + clipH) * .5
                clipW = (targetW + clipW) * .5
                clipH = (targetH + clipH) * .5
            }
            grab == GRAB_BOTTOM_RIGHT -> {
                clipW = (targetW + clipW) * .5
                clipH = (targetH + clipH) * .5
            }
            grab == GRAB_BOTTOM_LEFT -> {
                clipH = (targetH + clipH) * .5
                clipX = clipX + clipW - (targetW + clipW) * .5
                clipW = (targetW + clipW) * .5
            }
            grab and GRAB_HORIZONTAL != 0 -> {
                val off = targetH - clipH
                clipH += off
                clipY -= .5 * off
            }
            grab and GRAB_VERTICAL != 0 -> {
                val off = targetW - clipW
                clipW += off
                clipX -= .5 * off
            }
        }
        if (clipX < maxX) {
            val prevH = clipH
            clipH *= (clipW + clipX - maxX) / clipW
            clipW = clipW + clipX - maxX
            clipX = maxX
            if (grab and GRAB_TOP != 0) clipY += prevH - clipH
        }
        if (clipY < maxY) {
            val prevW = clipW
            clipW *= (clipH + clipY - maxY) / clipH
            clipH = clipH + clipY - maxY
            clipY = maxY
            if (grab and GRAB_LEFT != 0) clipX += prevW - clipW
        }
        if (clipX + clipW > maxX + maxW) {
            val prevH = clipH
            clipH *= (maxX + maxW - clipX) / clipW
            clipW = maxX + maxW - clipX
            if (grab and GRAB_TOP != 0) clipY += prevH - clipH
        }
        if (clipY + clipH > maxY + maxH) {
            val prevW = clipW
            clipW *= (maxY + maxH - clipY) / clipH
            clipH = maxY + maxH - clipY
            if (grab and GRAB_LEFT != 0) clipX += prevW - clipW
        }
        return Clip(clip(clipX).toFloat(), clip(clipY).toFloat(), clampD(clipW, 0.0, 1.0 - clipX).toFloat(), clampD(clipH, 0.0, 1.0 - clipY).toFloat())
    }

    /**
     * `_gui_get_grab`: desktop's hit test, [pzx], [pzy] as fractions of the picture, [border] in pixels of a
     * picture shown [wd] x [ht] pixels large. Kept for the tests; the app uses [hitTest].
     */
    fun desktopGrab(g: Clip, pzx: Float, pzy: Float, border: Float, wd: Float, ht: Float): Int {
        var grab = GRAB_NONE
        if (!(pzx < g.x || pzx > g.x + g.w || pzy < g.y || pzy > g.y + g.h)) {
            grab = GRAB_CENTER
            var hBorder = border / wd
            var vBorder = border / ht
            if (!(g.x != 0f || g.y != 0f || g.w != 1.0f || g.h != 1.0f)) { hBorder = 0.45f; vBorder = 0.45f }
            if (pzx >= g.x && pzx < g.x + hBorder && pzx - g.x < 0.5f * g.w) grab = grab or GRAB_LEFT
            else if (pzx <= g.x + g.w && pzx > (g.w + g.x) - hBorder) grab = grab or GRAB_RIGHT
            if (pzy >= g.y && pzy < g.y + vBorder && pzy - g.y < 0.5f * g.h) grab = grab or GRAB_TOP
            else if (pzy <= g.y + g.h && pzy > (g.h + g.y) - vBorder) grab = grab or GRAB_BOTTOM
        }
        return grab
    }

    /**
     * The app's hit test for a finger. Desktop's regions (borders inside the frame, the rest of the frame moves
     * it) with these differences, all for touch:
     * - a border is [touchX] x [touchY] deep (the interface specification's touch target, as fractions of the
     *   picture), but at most a third of the frame's width or height, so the middle of the frame always moves it
     *   (desktop gives a small frame's whole area to its borders);
     * - a border also reaches [touchX] / 2 and [touchY] / 2 outside the frame, so a side lying on the picture's
     *   edge can be taken from the surround next to it (on a phone the picture's edge is close to the screen's);
     * - outside those reaches nothing is grabbed ([GRAB_NONE]): the touch pans and zooms the photo.
     * Returns crop.c's grab bits: a corner is two bits, [GRAB_CENTER] moves the frame.
     */
    fun hitTest(g: Clip, px: Float, py: Float, touchX: Float, touchY: Float): Int {
        val outX = touchX / 2f
        val outY = touchY / 2f
        if (px < g.x - outX || px > g.right + outX || py < g.y - outY || py > g.bottom + outY) return GRAB_NONE
        val inX = min(touchX, g.w / 3f)
        val inY = min(touchY, g.h / 3f)
        // within the reach of a side, but beyond the frame along the other axis: only near a corner
        val nearX = px >= g.x - outX && px <= g.right + outX
        val nearY = py >= g.y - outY && py <= g.bottom + outY
        if (!(nearX && nearY)) return GRAB_NONE
        var grab = GRAB_CENTER
        if (px < g.x + inX && px - g.x < 0.5f * g.w) grab = grab or GRAB_LEFT
        else if (px > g.right - inX) grab = grab or GRAB_RIGHT
        if (py < g.y + inY && py - g.y < 0.5f * g.h) grab = grab or GRAB_TOP
        else if (py > g.bottom - inY) grab = grab or GRAB_BOTTOM
        // outside the frame along one axis only counts with that axis's side grabbed
        val outsideX = px < g.x || px > g.right
        val outsideY = py < g.y || py > g.bottom
        if (outsideX && grab and GRAB_HORIZONTAL == 0) return GRAB_NONE
        if (outsideY && grab and GRAB_VERTICAL == 0) return GRAB_NONE
        return grab
    }

    /**
     * One drag on the frame: `button_pressed` at [bx], [by] with [grab] (from [hitTest] or [desktopGrab]), then
     * [move] for every pointer position, as `mouse_moved` with the first button down. Positions are fractions of
     * the picture. [iwd] x [iht] is crop's input; [aspect] the ratio from [aspect] (`_aspect_ratio_get`).
     */
    class Drag(start: Clip, grab0: Int, private val bx: Float, private val by: Float,
               private val aspect: Float, private val iwd: Double, private val iht: Double) {
        /** desktop's `g->cropping`: [GRAB_ALL] for a move, the side bits for a resize, [GRAB_NONE] for nothing */
        val cropping: Int
        private val handleX: Float
        private val handleY: Float
        var clip: Clip = start
            private set

        init {
            var hx = 0f
            var hy = 0f
            var c = grab0
            if (c == GRAB_CENTER) {
                c = GRAB_ALL
                hx = start.x
                hy = start.y
            } else {
                if (c and GRAB_LEFT != 0) hx = bx - start.x
                if (c and GRAB_TOP != 0) hy = by - start.y
                if (c and GRAB_RIGHT != 0) hx = bx - (start.w + start.x)
                if (c and GRAB_BOTTOM != 0) hy = by - (start.h + start.y)
            }
            cropping = c
            handleX = hx
            handleY = hy
        }

        fun move(pzx: Float, pzy: Float): Clip {
            if (cropping == GRAB_NONE) return clip
            val maxX = 0f
            val maxY = 0f
            val maxW = 1f
            val maxH = 1f
            var x = clip.x
            var y = clip.y
            var w = clip.w
            var h = clip.h
            if (cropping == GRAB_ALL) {
                x = min(maxW + maxX - w, max(maxX, handleX + pzx - bx))
                y = min(maxH + maxY - h, max(maxY, handleY + pzy - by))
            } else {
                if (cropping and GRAB_LEFT != 0) {
                    val oldX = x
                    x = min(max(maxX, pzx - handleX), x + w - MIN_CROP_SIZE)
                    w = oldX + w - x
                }
                if (cropping and GRAB_TOP != 0) {
                    val oldY = y
                    y = min(max(maxY, pzy - handleY), y + h - MIN_CROP_SIZE)
                    h = oldY + h - y
                }
                if (cropping and GRAB_RIGHT != 0) w = max(MIN_CROP_SIZE, min(maxW + maxX, pzx - x - handleX))
                if (cropping and GRAB_BOTTOM != 0) h = max(MIN_CROP_SIZE, min(maxH + maxY, pzy - y - handleY))
                if (x + w > maxW + maxX) w = maxW + maxX - x
                if (y + h > maxH + maxY) h = maxH + maxY - y
            }
            clip = aspectApply(Clip(x, y, w, h), aspect, cropping, iwd, iht)
            return clip
        }
    }

    /** `_reduce_aligners`: the ratio's terms divided by their common factors up to 7; whether it is exact. */
    private fun reduceAligners(w0: Int, h0: Int): Triple<Int, Int, Boolean> {
        var w = max(1, abs(w0))
        var h = max(1, abs(h0))
        for (i in 7 downTo 2) {
            while (w % i == 0 && h % i == 0) { w /= i; h /= i }
        }
        return Triple(w, h, w <= 16 && h <= 16 && (w > 1 || h > 1))
    }

    /**
     * `_commit_box` for crop's input [iwd] x [iht] pixels (no distortion after crop): the frame in pixels, rounded
     * to whole multiples of an exact ratio, clamped, as the parameters (left, top, right, bottom).
     */
    fun commit(g: Clip, ratioD: Int, ratioN: Int, iwd: Int, iht: Int): CropAspect.Frame {
        val wd = iwd.toFloat()
        val ht = iht.toFloat()
        val p0 = floatArrayOf(g.x * wd, g.y * ht, (g.x + g.w) * wd, (g.y + g.h) * ht)
        if (ratioD != 0 && ratioN != 0) {
            val landscape = iwd >= iht
            val flipped = ratioD < 0
            val rd = max(1, abs(ratioD)).toFloat()
            val rn = max(1, ratioN).toFloat()
            val aspect = if (flipped) rn / rd else rd / rn
            var width = p0[2] - p0[0]
            var height = p0[3] - p0[1]
            if (width > height) height = if (landscape) width / aspect else width * aspect
            else width = if (landscape) height * aspect else height / aspect
            val (aw, ah, exact) = reduceAligners(if (flipped == landscape) ratioN else ratioD, if (flipped == landscape) ratioD else ratioN)
            val dw = if (exact) width.toInt() % aw else 0
            val dh = if (exact) height.toInt() % ah else 0
            p0[0] += dw.toFloat() / 2.0f
            p0[1] += dh.toFloat() / 2.0f
            p0[2] = p0[0] + width - dw
            p0[3] = p0[1] + height - dh
        }
        return CropAspect.Frame(
            clampf(p0[0] / wd, 0f, 1f - MIN_CROP_SIZE).toDouble(),
            clampf(p0[1] / ht, 0f, 1f - MIN_CROP_SIZE).toDouble(),
            clampf(p0[2] / wd, MIN_CROP_SIZE, 1f).toDouble(),
            clampf(p0[3] / ht, MIN_CROP_SIZE, 1f).toDouble(),
        )
    }

    /** Whether two frames as parameters differ as desktop's `_commit_box` asks (more than 1e-6 on a side). */
    fun changed(a: CropAspect.Frame, b: CropAspect.Frame, eps: Double = 1e-6): Boolean =
        abs(a.left - b.left) > eps || abs(a.top - b.top) > eps || abs(a.right - b.right) > eps || abs(a.bottom - b.bottom) > eps

    // ------------------------------------------------------------------ brief P3a deliverable 4: straighten

    private const val M_PI_F = 3.14159265358979323846f
    private const val M_PI_2F = 1.57079632679489661923f
    private const val M_PI_4F = 0.78539816339744830962f

    /**
     * src/iop/ashift.c `_calculate_straightening`: the angle (degrees) that a line drawn from [bzx], [bzy] to [pzx],
     * [pzy] (fractions of the picture shown [wd] x [ht] pixels large at [zoomScale]) adds to rotate and perspective's
     * `rotation` (desktop: the rotation slider, whose factor is -1, is set to its value minus this angle, so the
     * parameter becomes `rotation + angle`). The line is levelled to the nearer axis: horizontal within 45°, else
     * vertical. Lines shorter than [minLength] pixels (desktop: 25 px) give 0.
     *
     * Desktop takes the line back through the modules after rotate and perspective
     * (`dt_dev_distort_backtransform_plus`); with crop off (the crop tool's picture) that is orientation (flip)
     * only. A turn by 90°, 180° or 270° does not change the result (the deviation from the nearer axis keeps its
     * sign); a mirrored orientation reverses it: [mirrored].
     */
    fun straighten(pzx: Float, pzy: Float, bzx: Float, bzy: Float, wd: Float, ht: Float, zoomScale: Float,
                   minLength: Float = 25f, mirrored: Boolean = false): Float {
        var dx = (pzx - bzx) * wd
        var dy = (pzy - bzy) * ht
        if (Math.sqrt((dx * dx + dy * dy).toDouble()) * zoomScale < minLength) return 0.0f
        // pts = { 0, 0, dx, dy } back-transformed; dx = pts[0] - pts[2], dy = pts[1] - pts[3]
        val bx = 0f - dx
        val by = if (mirrored) -(0f - dy) else 0f - dy
        dx = bx
        dy = by
        if (dx < 0) { dx = -dx; dy = -dy }
        val angle = kotlin.math.atan2(dy, dx)
        if (!(angle >= -M_PI_2F && angle <= M_PI_2F)) return 0.0f
        var close = angle
        close = if (close > M_PI_4F) M_PI_2F - close else if (close < -M_PI_4F) -M_PI_2F - close else -close
        var a = close / M_PI_F * 180f
        if (a < -180f) a += 360f
        if (a > 180f) a -= 360f
        return a
    }

    /**
     * Whether darktable's orientation value (flip's `orientation`, `dt_image_orientation_t`: FLIP_Y 1, FLIP_X 2,
     * SWAP_XY 4) mirrors the picture: an odd number of reflections. "autodetect" (-1, from the file's Exif) counts
     * as not mirrored: cameras write turns, not mirrors.
     */
    fun mirrored(orientation: Int): Boolean = orientation >= 0 && Integer.bitCount(orientation and 7) % 2 == 1

    /**
     * The cropped picture's size as desktop shows it while the frame is dragged (`gui_post_expose`): the frame's
     * width and height in pixels of crop's input [iwd] x [iht], rounded down, less what an exact ratio rounds off.
     */
    fun shownSize(g: Clip, ratioD: Int, ratioN: Int, iwd: Int, iht: Int): Pair<Int, Int> {
        val width = kotlin.math.floor(iwd * g.w).toInt()
        val height = kotlin.math.floor(iht * g.h).toInt()
        val (aw, ah, exact) = reduceAligners(if (width >= height) ratioD else ratioN, if (width >= height) ratioN else ratioD)
        val dw = if (exact) width % aw else 0
        val dh = if (exact) height % ah else 0
        return (width - dw) to (height - dh)
    }
}
