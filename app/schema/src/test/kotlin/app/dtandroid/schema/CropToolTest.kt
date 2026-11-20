// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Brief P3a deliverable 6 (JVM): the crop tool's arithmetic against values computed by crop.c's own functions
 * (`tools/p3a-crop-reference.py`, output in `src/test/resources/p3a-crop-reference.json`): `_aspect_apply` for
 * every grab, every kind of ratio (freehand, original image both ways, square, 3:2 and flipped, 16:9 and flipped,
 * the ISO and print entries, XPan), frames inside and touching the borders, on landscape, portrait (a photo turned
 * by 90°: crop's input is portrait then) and square input; drags of every corner, every edge and the whole frame,
 * pulled beyond the picture's borders and back, under six ratios; and `_commit_box`'s rounding to the ratio.
 * The app's touch hit test is checked on its own (it is not desktop's, see [CropTool.hitTest]).
 */
class CropToolTest {
    private val schema by lazy { SchemaLoader.fromResources() }
    private val spec by lazy { schema.modules.getValue("crop").aspect!! }
    private val ref by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResourceAsStream("p3a-crop-reference.json")!!.readBytes().decodeToString()).jsonObject
    }

    private fun cases(k: String) = (ref[k] as JsonArray).map { it.jsonObject }
    private fun JsonObject.i(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.floats(k: String) = getValue(k).jsonArray.map { it.jsonPrimitive.double.toFloat() }
    private fun clipOf(v: List<Float>) = CropTool.Clip(v[0], v[1], v[2], v[3])

    /** Float results of the same float operations: equal to the last bit, allowed one unit of the 7th digit. */
    private fun near(what: String, want: List<Float>, got: CropTool.Clip) {
        val g = listOf(got.x, got.y, got.w, got.h)
        for (k in 0..3) assertTrue("$what: component $k want ${want[k]} got ${g[k]} (all: $want vs $g)", abs(want[k] - g[k]) <= 2e-6f)
    }

    @Test fun aspectApplyEqualsDesktopForEveryGrabRatioAndOrientation() {
        val all = cases("apply")
        assertTrue("reference cases: ${all.size}", all.size >= 1800)
        var n = 0
        for (c in all) {
            val w = c.i("w").toDouble()
            val h = c.i("h").toDouble()
            val a = CropTool.aspect(spec, c.i("d"), c.i("n"), w, h)
            val what = "w ${c.i("w")} h ${c.i("h")} d ${c.i("d")} n ${c.i("n")} frame ${c.floats("frame")} grab ${c.i("grab")}"
            assertEquals("aspect $what", c.getValue("aspect").jsonPrimitive.double.toFloat(), a, 1e-6f)
            near(what, c.floats("out"), CropTool.aspectApply(clipOf(c.floats("frame")), a, c.i("grab"), w, h))
            n++
        }
        assertEquals(all.size, n)
    }

    @Test fun dragsEqualDesktopStepByStep() {
        val all = cases("drag")
        assertTrue("reference drags: ${all.size}", all.size >= 160)
        val grabs = HashSet<Int>()
        for (c in all) {
            val w = c.i("w").toDouble()
            val h = c.i("h").toDouble()
            val a = CropTool.aspect(spec, c.i("d"), c.i("n"), w, h)
            // the frame as desktop shows it before the press (gui_post_expose applies the ratio)
            val start = CropTool.aspectApply(CropTool.Clip(0.1f, 0.2f, 0.5f, 0.5f), a, CropTool.GRAB_HORIZONTAL or CropTool.GRAB_VERTICAL, w, h)
            val what = "w ${c.i("w")} h ${c.i("h")} d ${c.i("d")} n ${c.i("n")} press ${c.floats("press")}"
            near("start $what", c.floats("start"), start)
            val press = c.floats("press")
            // desktop's preview of the driver: 1000 px on the longer side, 30 px borders at zoom 1
            val pw = if (w >= h) 1000f else (1000.0 * w / h).toFloat()
            val ph = if (w >= h) (1000.0 * h / w).toFloat() else 1000f
            val grab = CropTool.desktopGrab(start, press[0], press[1], 30f, pw, ph)
            val d = CropTool.Drag(start, grab, press[0], press[1], a, w, h)
            assertEquals("grab $what", c.i("grab"), d.cropping)
            grabs += d.cropping
            for ((k, s) in c.getValue("steps").jsonArray.map { it.jsonObject }.withIndex()) {
                val to = s.floats("to")
                near("$what step $k to $to", s.floats("out"), d.move(to[0], to[1]))
            }
        }
        // every corner, every edge and the move were exercised; presses that fall outside a frame the ratio made
        // smaller grab nothing, on desktop as here (GRAB_NONE: the frame does not move)
        assertEquals(setOf(1, 2, 4, 8, 3, 6, 12, 9, 15, CropTool.GRAB_NONE), grabs)
    }

    @Test fun commitRoundsAsDesktop() {
        val all = cases("commit")
        assertTrue("reference commits: ${all.size}", all.size >= 160)
        for (c in all) {
            val f = CropTool.commit(clipOf(c.floats("frame")), c.i("d"), c.i("n"), c.i("w"), c.i("h"))
            val p = c.floats("params")
            val what = "w ${c.i("w")} h ${c.i("h")} d ${c.i("d")} n ${c.i("n")} frame ${c.floats("frame")}"
            near(what, p, CropTool.Clip(f.left.toFloat(), f.top.toFloat(), f.right.toFloat(), f.bottom.toFloat()))
        }
    }

    @Test fun frameFromTheParametersAsGuiFocus() {
        assertEquals(CropTool.Clip(0.1f, 0.2f, 0.5f, 0.5f), CropTool.Clip.fromParams(0.1f, 0.2f, 0.6f, 0.7f).let {
            CropTool.Clip(it.x, it.y, Math.round(it.w * 1e6f) / 1e6f, Math.round(it.h * 1e6f) / 1e6f)
        })
        // defaults (0, 0, 1, 1) and a frame narrower than the minimum
        assertEquals(CropTool.Clip.FULL, CropTool.Clip.fromParams(0f, 0f, 1f, 1f))
        assertEquals(CropTool.MIN_CROP_SIZE, CropTool.Clip.fromParams(0.5f, 0.5f, 0.5f, 0.9f).w, 0f)
    }

    // ------------------------------------------------------------------ the app's hit test (touch)

    private val frame = CropTool.Clip(0.2f, 0.2f, 0.6f, 0.6f)
    private val t = 0.05f // a 44 dp target on a picture about 880 dp wide

    @Test fun cornersEdgesAndInside() {
        assertEquals(CropTool.GRAB_TOP_LEFT, CropTool.hitTest(frame, 0.21f, 0.21f, t, t))
        assertEquals(CropTool.GRAB_TOP_RIGHT, CropTool.hitTest(frame, 0.79f, 0.21f, t, t))
        assertEquals(CropTool.GRAB_BOTTOM_RIGHT, CropTool.hitTest(frame, 0.79f, 0.79f, t, t))
        assertEquals(CropTool.GRAB_BOTTOM_LEFT, CropTool.hitTest(frame, 0.21f, 0.79f, t, t))
        assertEquals(CropTool.GRAB_LEFT, CropTool.hitTest(frame, 0.21f, 0.5f, t, t))
        assertEquals(CropTool.GRAB_TOP, CropTool.hitTest(frame, 0.5f, 0.21f, t, t))
        assertEquals(CropTool.GRAB_RIGHT, CropTool.hitTest(frame, 0.79f, 0.5f, t, t))
        assertEquals(CropTool.GRAB_BOTTOM, CropTool.hitTest(frame, 0.5f, 0.79f, t, t))
        assertEquals(CropTool.GRAB_CENTER, CropTool.hitTest(frame, 0.5f, 0.5f, t, t))
    }

    @Test fun aSideIsReachedFromJustOutsideTheFrameNothingFurther() {
        // half a target outside: the side; further: nothing (the touch pans the photo)
        assertEquals(CropTool.GRAB_LEFT, CropTool.hitTest(frame, 0.2f - 0.02f, 0.5f, t, t))
        assertEquals(CropTool.GRAB_TOP_LEFT, CropTool.hitTest(frame, 0.18f, 0.18f, t, t))
        assertEquals(CropTool.GRAB_NONE, CropTool.hitTest(frame, 0.2f - 0.03f, 0.5f, t, t))
        assertEquals(CropTool.GRAB_NONE, CropTool.hitTest(frame, 0.05f, 0.05f, t, t))
        // a frame on the picture's edges: its sides are taken from the surround next to the picture
        val full = CropTool.Clip.FULL
        assertEquals(CropTool.GRAB_LEFT, CropTool.hitTest(full, -0.02f, 0.5f, t, t))
        assertEquals(CropTool.GRAB_BOTTOM_RIGHT, CropTool.hitTest(full, 1.02f, 1.02f, t, t))
        assertEquals(CropTool.GRAB_CENTER, CropTool.hitTest(full, 0.5f, 0.5f, t, t))
    }

    @Test fun theMiddleOfASmallFrameStillMovesIt() {
        val small = CropTool.Clip(0.4f, 0.4f, 0.06f, 0.06f)
        assertEquals(CropTool.GRAB_CENTER, CropTool.hitTest(small, 0.43f, 0.43f, t, t))
        assertEquals(CropTool.GRAB_TOP_LEFT, CropTool.hitTest(small, 0.405f, 0.405f, t, t))
        assertEquals(CropTool.GRAB_BOTTOM_RIGHT, CropTool.hitTest(small, 0.47f, 0.47f, t, t))
    }

    @Test fun theFrameNeverLeavesThePicture() {
        // a move far outside, a corner far outside: inside 0..1 always, with the ratio kept
        for ((d, n) in listOf(0 to 0, 3 to 2, -3 to 2, 1 to 1)) {
            val a = CropTool.aspect(spec, d, n, 6000.0, 4000.0)
            val start = CropTool.aspectApply(frame, a, CropTool.GRAB_ALL, 6000.0, 4000.0)
            for ((grab, to) in listOf(CropTool.GRAB_CENTER to (5f to 5f), CropTool.GRAB_CENTER to (-5f to -5f),
                CropTool.GRAB_TOP_LEFT to (-3f to -3f), CropTool.GRAB_BOTTOM_RIGHT to (4f to 4f), CropTool.GRAB_RIGHT to (9f to 0.5f))) {
                val drag = CropTool.Drag(start, grab, 0.5f, 0.5f, a, 6000.0, 4000.0)
                val c = drag.move(to.first, to.second)
                assertTrue("$d:$n grab $grab -> $c", c.x >= 0f && c.y >= 0f && c.right <= 1f + 1e-6f && c.bottom <= 1f + 1e-6f)
                if (a > 0f) {
                    val ratio = c.w * 6000.0 / (c.h * 4000.0)
                    assertEquals("$d:$n grab $grab ratio of $c", a.toDouble(), ratio, 1e-4)
                }
            }
        }
    }
}
