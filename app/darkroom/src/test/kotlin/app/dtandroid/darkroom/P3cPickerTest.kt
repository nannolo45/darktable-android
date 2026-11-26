// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.CoordsResult
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.PickRequest
import app.dtandroid.engine.api.PickResult
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Binding
import app.dtandroid.schema.PickSelection
import app.dtandroid.schema.PickerTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3c deliverable 7 (JVM): the picker tool on the stand-in engine. Arming (and desktop's switching the module
 * on), one picker at a time, disarming when the module is left, point and area gestures, the selection kept in
 * original-image coordinates under a rotation and a crop, the view's mapping under zoom, each module's action called
 * with the right area and settings, the readout, and the read-only case.
 */
class P3cPickerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val wb = InstanceKey("temperature", 0)
    private val exposure = InstanceKey("exposure", 0)
    private val levels = InstanceKey("rgblevels", 0)
    private val curve = InstanceKey("rgbcurve", 0)
    private val calibration = InstanceKey("channelmixerrgb", 0)

    /**
     * The stand-in engine, recording module actions and picks; with [cropX] set, its geometry also crops the picture
     * to x in cropX..1 (the stand-in's own geometry is only the orientation), so a selection must follow a crop.
     */
    private class Hooked(val e: DarktableEngine) : DarktableEngine by e {
        val actions = java.util.Collections.synchronizedList(ArrayList<Triple<String, String, JsonObject>>())
        val picks = java.util.Collections.synchronizedList(ArrayList<Pair<String?, PickRequest>>())
        @Volatile var cropX: Double? = null
        override suspend fun moduleAction(op: String, priority: Int, action: String, args: JsonObject): JsonElement {
            actions += Triple(op, action, args)
            // the stand-in keeps rgblevels' levels[3][3] as one row of nine (the schema does not give the dimensions):
            // the second and third channels' picks are answered here, unchanged
            if (op == "rgblevels" && action == "pick" && (args["channel"]?.jsonPrimitive?.content?.toInt() ?: 0) != 0)
                return JsonObject(mapOf("changed" to JsonPrimitive(false)))
            return e.moduleAction(op, priority, action, args)
        }
        override suspend fun colorPick(op: String?, priority: Int, request: PickRequest): PickResult {
            picks += op to request
            // the stand-in's picture is not cropped: pick where the uncropped picture has the selection
            val r = cropX?.let { c -> request.copy(area = request.area?.let { a -> listOf(c + a[0] * (1 - c), a[1], c + a[2] * (1 - c), a[3]) },
                point = request.point?.let { p -> listOf(c + p[0] * (1 - c), p[1]) }) } ?: request
            return e.colorPick(op, priority, r)
        }
        override suspend fun coordsTransform(direction: CoordsDirection, points: List<Pair<Double, Double>>, options: RenderOptions): CoordsResult {
            val c = cropX ?: return e.coordsTransform(direction, points, options)
            // the crop comes after the orientation: to_processed = orientation, then crop; to_original the reverse
            return if (direction == CoordsDirection.TO_PROCESSED) {
                val r = e.coordsTransform(direction, points, options)
                r.copy(points = r.points.map { p -> p?.let { (it.first - c) / (1 - c) to it.second } })
            } else e.coordsTransform(direction, points.map { (c + it.first * (1 - c)) to it.second }, options)
        }
        /** the last action of [op] that is not a read (get_wb, list_*: the darkroom reads those after every change) */
        fun last(op: String) = actions.last { it.first == op && !reads(it.second) }
        fun changes() = actions.filter { !reads(it.second) }
        private fun reads(a: String) = a.startsWith("get_") || a.startsWith("list_")
    }

    private fun controller(index: Int = 0): Pair<DarkroomController, Hooked> {
        val dir = tmp.root
        val engine = Hooked(InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), index, filmstrip = false, thumbnails = false) }
        lastController = c
        return c to engine
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what; picker ${lastController?.state?.value?.picker}, message ${lastController?.state?.value?.message}")
        }

    private var lastController: DarkroomController? = null

    private suspend fun arm(c: DarkroomController, key: InstanceKey, control: Int) {
        if (c.state.value.expanded != key) c.toggleExpanded(key)
        c.togglePicker(key, control)
        assertTrue("armed", c.pickerArmed(c.state.value, key, control))
        waitFor("readout") { c.state.value.picker?.readout?.isNotEmpty() == true }
    }

    private fun JsonObject.area() = (this["area"] as? JsonArray)?.map { it.jsonPrimitive.double }
    private fun JsonObject.point() = (this["point"] as? JsonArray)?.map { it.jsonPrimitive.double }
    private fun close(a: List<Double>?, b: List<Double>, tol: Double = 1e-6) {
        assertNotNull(a)
        assertEquals(b.size, a!!.size)
        for (i in b.indices) assertEquals("[$i] of $a", b[i], a[i], tol)
    }

    // ------------------------------------------------------------------ arming

    @Test fun armingPicksAtOnceWithDesktopsFirstSelection() = runBlocking {
        val (c, e) = controller()
        val items0 = c.state.value.history.items.size
        arm(c, wb, 4)
        val p = c.state.value.picker!!
        assertEquals(PickSelection.DEFAULT_AREA, p.selection)
        val (op, action, args) = e.last("temperature")
        assertEquals("wb_from_area", action)
        close(args.area(), listOf(0.02, 0.02, 0.98, 0.98))
        assertEquals("one history item", items0 + 1, c.state.value.history.items.size)
        // the readout: desktop's global picker (op null) on the same area, mean / min / max
        assertTrue(e.picks.any { it.first == null && it.second.area == listOf(0.02, 0.02, 0.98, 0.98) })
        assertEquals(3, c.state.value.picker!!.readout.size)
        assertTrue(c.state.value.picker!!.readout[0].startsWith("mean  RGB "))
        assertTrue(c.state.value.picker!!.readout[1].startsWith("min  RGB "))
        assertTrue(c.state.value.picker!!.readout[2].startsWith("max  RGB "))
        assertEquals(op, "temperature")
    }

    @Test fun armingASwitchedOffModuleSwitchesItOn() = runBlocking {
        val (c, e) = controller()
        assertFalse(c.state.value.modules.first { it.key == levels }.enabled)
        arm(c, levels, 7)
        assertTrue(c.state.value.modules.first { it.key == levels }.enabled)
        val (_, action, args) = e.last("rgblevels")
        assertEquals("pick", action)
        assertEquals("black", args["which"]!!.jsonPrimitive.content)
        // a point picker starts with desktop's point in the middle
        close(args.point(), listOf(0.5, 0.5))
        assertEquals(0, args["channel"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun oneAtATimeAndLeavingTheModuleDisarms() = runBlocking {
        val (c, _) = controller()
        arm(c, levels, 7)
        arm(c, levels, 9)
        assertFalse(c.pickerArmed(c.state.value, levels, 7))
        assertTrue(c.pickerArmed(c.state.value, levels, 9))
        // its button again: disarmed
        c.togglePicker(levels, 9)
        assertNull(c.state.value.picker)
        arm(c, levels, 8)
        // another module's panel: disarmed
        c.toggleExpanded(exposure)
        assertNull(c.state.value.picker)
        arm(c, exposure, 5)
        // the panel closed: disarmed
        c.toggleExpanded(exposure)
        assertNull(c.state.value.picker)
        arm(c, exposure, 5)
        // another photo: disarmed
        c.openPhoto(1)
        assertNull(c.state.value.picker)
    }

    @Test fun readOnlyPhotoArmsNothing() = runBlocking {
        val (c, e) = controller(index = InterfacePreview.READ_ONLY_INDEX)
        assertTrue(c.state.value.readOnly)
        c.toggleExpanded(wb)
        c.togglePicker(wb, 4)
        delay(50)
        assertNull(c.state.value.picker)
        assertTrue(e.changes().isEmpty())
    }

    // ------------------------------------------------------------------ gestures

    @Test fun aTapPlacesAPointADragDrawsAnAreaAndTheAreaMovesAndResizes() = runBlocking {
        val (c, e) = controller()
        arm(c, curve, 6) // "show color": point and area, values only
        assertEquals(PickSelection.DEFAULT_POINT, c.state.value.picker!!.selection)
        // a tap away from the point: a point there
        c.pickerTap(0.25f, 0.75f, 0.05f, 0.05f)
        waitFor("point picked") { e.picks.lastOrNull()?.second?.point?.let { it[0] in 0.2499..0.2501 } == true }
        close(c.state.value.picker!!.selection!!.point, listOf(0.25, 0.75), 1e-6)
        assertTrue("show color changes nothing", e.actions.none { it.first == "rgbcurve" })
        // a drag away from it: a new area
        c.pickerDragStart(PickerTool.GRAB_NONE, 0.1f, 0.1f)
        c.pickerDragMove(0.3f, 0.2f)
        c.pickerDragMove(0.4f, 0.5f)
        assertTrue(c.state.value.picker!!.dragging)
        c.pickerDragEnd()
        waitFor("area picked") { e.picks.lastOrNull()?.second?.area != null }
        close(c.state.value.picker!!.selection!!.area, listOf(0.1, 0.1, 0.4, 0.5), 1e-6)
        assertEquals(3, run { waitFor("readout of an area") { c.state.value.picker!!.readout.size == 3 }; c.state.value.picker!!.readout.size })
        // a finger inside the area moves it
        val grab = c.pickerHit(0.25f, 0.3f, 0.02f, 0.02f)
        assertEquals(PickerTool.GRAB_MOVE, grab)
        c.pickerDragStart(grab, 0.25f, 0.3f)
        c.pickerDragMove(0.35f, 0.35f)
        c.pickerDragEnd()
        close(c.state.value.picker!!.selection!!.area, listOf(0.2, 0.15, 0.5, 0.55), 1e-6)
        // its bottom right corner resizes it
        val corner = c.pickerHit(0.5f, 0.55f, 0.02f, 0.02f)
        assertEquals(PickerTool.GRAB_RIGHT or PickerTool.GRAB_BOTTOM, corner)
        c.pickerDragStart(corner, 0.5f, 0.55f)
        c.pickerDragMove(0.6f, 0.65f)
        c.pickerDragEnd()
        close(c.state.value.picker!!.selection!!.area, listOf(0.2, 0.15, 0.6, 0.65), 1e-5)
        waitFor("picked the resized area") { e.picks.lastOrNull()?.second?.area?.let { it[2] in 0.5999..0.6001 } == true }
        // a second finger before the area moved: a pinch, the area goes back
        val before = c.state.value.picker!!.selection
        c.pickerDragStart(c.pickerHit(0.4f, 0.4f, 0.02f, 0.02f), 0.4f, 0.4f)
        c.pickerDragCancel()
        assertEquals(before, c.state.value.picker!!.selection)
    }

    @Test fun anAreaPickerGetsASmallAreaFromATapAndAPointPickerFollowsTheFinger() = runBlocking {
        val (c, e) = controller()
        arm(c, wb, 4)
        val n = e.changes().size
        c.pickerTap(0.5f, 0.5f, 0.1f, 0.1f)
        waitFor("picked") { e.changes().size > n }
        close(e.last("temperature").third.area(), listOf(0.45, 0.45, 0.55, 0.55), 1e-6)
        // levels' black: a drag away from the point carries the point
        arm(c, levels, 7)
        val m = e.changes().size
        c.pickerDragStart(PickerTool.GRAB_NONE, 0.2f, 0.3f)
        c.pickerDragMove(0.3f, 0.4f)
        c.pickerDragEnd()
        waitFor("picked") { e.changes().size > m }
        close(e.last("rgblevels").third.point(), listOf(0.3, 0.4), 1e-6)
    }

    @Test fun theViewsMappingKeepsTheSelectionOnThePictureAtAnyZoom() {
        // a 600 x 400 picture in a 400 x 300 view: fit 2/3; zoomed 3x on (0.25, 0.75)
        for ((zoom, cx, cy) in listOf(Triple(1f, 0.5f, 0.5f), Triple(3f, 0.25f, 0.75f), Triple(1.7f, 0.6f, 0.4f))) {
            val fit = minOf(400f / 600, 300f / 400)
            for ((fx, fy) in listOf(0.1f to 0.2f, 0.25f to 0.75f, 0.9f to 0.5f)) {
                val (vx, vy) = PhotoGeometry.viewPoint(fx, fy, 400, 300, 600, 400, fit, zoom, cx, cy)
                val (px, py) = PhotoGeometry.picturePoint(vx, vy, 400, 300, 600, 400, fit, zoom, cx, cy)
                assertEquals(fx, px, 1e-5f)
                assertEquals(fy, py, 1e-5f)
            }
            // the view's centre is the picture's centre of view
            val (mx, my) = PhotoGeometry.picturePoint(200f, 150f, 400, 300, 600, 400, fit, zoom, cx, cy)
            assertEquals(cx, mx, 1e-5f)
            assertEquals(cy, my, 1e-5f)
        }
        // zoomed in, a finger's move of 10 view pixels is a smaller part of the picture than at fit
        val fit = 400f / 600
        val a = PhotoGeometry.picturePoint(210f, 150f, 400, 300, 600, 400, fit, 4f, 0.5f, 0.5f).first -
            PhotoGeometry.picturePoint(200f, 150f, 400, 300, 600, 400, fit, 4f, 0.5f, 0.5f).first
        assertEquals(10f / (600 * fit * 4), a, 1e-6f)
    }

    // ------------------------------------------------------------------ geometry changes

    @Test fun theSelectionFollowsARotationOfTheEdit() = runBlocking {
        val (c, e) = controller()
        arm(c, curve, 6)
        c.pickerDragStart(PickerTool.GRAB_NONE, 0.1f, 0.2f)
        c.pickerDragMove(0.3f, 0.6f)
        c.pickerDragEnd()
        waitFor("area picked") { e.picks.lastOrNull()?.second?.area?.get(0)?.let { it in 0.0999..0.1001 } == true }
        // the orientation turned (another module than the picker's): the area is the same part of the picture
        val orig = e.coordsTransform(CoordsDirection.TO_ORIGINAL, PickSelection.area(0.1, 0.2, 0.3, 0.6).corners()).points
        c.setParam(InstanceKey("flip", 0), "orientation", JsonPrimitive("ORIENTATION_ROTATE_CW_90_DEG"))
        val now = e.coordsTransform(CoordsDirection.TO_PROCESSED, orig.map { it!! }).points
        val want = PickSelection.fromCorners(now, area = true)!!.area!!
        waitFor("selection mapped") { c.state.value.picker!!.selection!!.area!!.zip(want).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6 } }
        assertTrue("the turn moved it", kotlin.math.abs(want[0] - 0.1) > 0.05 || kotlin.math.abs(want[1] - 0.2) > 0.05)
        // and it picks there
        c.pickerApply()
        close(e.picks.last().second.area, want, 1e-6) // the finger's positions are floats
    }

    @Test fun theSelectionFollowsACropOfTheEdit() = runBlocking {
        val (c, e) = controller()
        arm(c, wb, 4)
        c.pickerDragStart(PickerTool.GRAB_NONE, 0.6f, 0.2f)
        c.pickerDragMove(0.8f, 0.4f)
        c.pickerDragEnd()
        waitFor("picked") { e.last("temperature").third.area()?.get(0)?.let { it in 0.5999..0.6001 } == true }
        // a crop that keeps x 0.5..1 of the picture: the area is now at (0.6 - 0.5) / 0.5 = 0.2 .. 0.6
        e.cropX = 0.5
        c.setParam(InstanceKey("colorbalancergb", 0), "vibrance", JsonPrimitive(0.1)) // another module changes
        waitFor("mapped") { c.state.value.picker!!.selection!!.area!![0] in 0.1999..0.2001 }
        close(c.state.value.picker!!.selection!!.area, listOf(0.2, 0.2, 0.6, 0.4), 1e-6)
        // an area the crop cut away: said so, nothing picked from it
        c.pickerDragStart(PickerTool.GRAB_NONE, 0.0f, 0.0f)
        c.pickerDragMove(0.1f, 0.1f)
        c.pickerDragEnd()
        waitFor("picked") { e.last("temperature").third.area()?.get(0)?.let { it < 0.001 } == true }
        e.cropX = 0.75
        c.setParam(InstanceKey("colorbalancergb", 0), "vibrance", JsonPrimitive(0.2))
        waitFor("outside") { c.state.value.picker!!.outside }
    }

    // ------------------------------------------------------------------ module actions and settings

    @Test fun exposuresSpotUsesItsSettingsAndMeasureWritesTheTargetBack() = runBlocking {
        val (c, e) = controller()
        arm(c, exposure, 5)
        var args = e.last("exposure").third
        assertEquals("spot", e.last("exposure").second)
        assertEquals("correction", args["mode"]!!.jsonPrimitive.content)
        assertEquals(50.0, args["lightness"]!!.jsonPrimitive.double, 0.0)
        close(args.area(), listOf(0.02, 0.02, 0.98, 0.98))
        // a new target in correction mode picks again (desktop's _spot_settings_changed_callback)
        val n = e.changes().size
        c.setSlider(exposure, Binding.Setting("exposure/lightness"), 30.0, false, 22)
        waitFor("picked again") { e.changes().size > n }
        assertEquals(30.0, e.last("exposure").third["lightness"]!!.jsonPrimitive.double, 0.0)
        // measure: the measured lightness becomes the target; changing the mode alone picks nothing
        val m = e.changes().size
        c.chooseSetting(exposure, 14, 1)
        delay(50)
        assertEquals(m, e.changes().size)
        c.pickerTap(0.5f, 0.5f, 0.1f, 0.1f)
        waitFor("measured") { e.changes().size > m }
        args = e.last("exposure").third
        assertEquals("measure", args["mode"]!!.jsonPrimitive.content)
        waitFor("target written back") { c.settingValue(c.state.value, "exposure", "exposure/lightness")?.content?.toDouble() != 30.0 }
        val shown = c.sliderValue(c.state.value, exposure, TestSetup.schema.modules.getValue("exposure").control(22) as app.dtandroid.schema.Control.Slider, emptyMap())
        assertEquals(c.settingValue(c.state.value, "exposure", "exposure/lightness")!!.content.toDouble(), shown!!, 1e-9)
    }

    @Test fun levelsTakeTheChannelOfTheShownTab() = runBlocking {
        val (c, e) = controller()
        c.toggleExpanded(levels)
        c.setParam(levels, "autoscale", JsonPrimitive("DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS"))
        c.chooseTab(levels, 1, 2)
        arm(c, levels, 8)
        assertEquals("grey", e.last("rgblevels").third["which"]!!.jsonPrimitive.content)
        assertEquals(2, e.last("rgblevels").third["channel"]!!.jsonPrimitive.content.toInt())
        // linked channels: the tabs are hidden, desktop picks for the first channel
        c.setParam(levels, "autoscale", JsonPrimitive("DT_IOP_RGBLEVELS_LINKED_CHANNELS"))
        c.pickerApply()
        assertEquals(0, e.last("rgblevels").third["channel"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun curveAndCalibrationCallTheirActions() = runBlocking {
        val (c, e) = controller()
        arm(c, curve, 7)
        assertEquals("set_nodes_from_area", e.last("rgbcurve").second)
        close(e.last("rgbcurve").third.area(), listOf(0.02, 0.02, 0.98, 0.98))
        assertEquals(0, e.last("rgbcurve").third["channel"]!!.jsonPrimitive.content.toInt())
        // color calibration: a neutral target is the CAT tab's own picker
        arm(c, calibration, 6)
        assertEquals("illuminant_from_area", e.last("channelmixerrgb").second)
        assertEquals(true, e.last("channelmixerrgb").third["use_mixing"]!!.jsonPrimitive.content.toBoolean())
        // a target with chroma: the area mapping; changing it while armed picks again
        val n = e.changes().size
        c.setSlider(calibration, Binding.Setting("channelmixerrgb/chroma"), 20.0, false, 26)
        waitFor("picked again") { e.changes().size > n }
        val (_, action, args) = e.last("channelmixerrgb")
        assertEquals("spot", action)
        val t = args["target"] as JsonObject
        assertEquals(20.0, t["chroma"]!!.jsonPrimitive.double, 0.0)
        assertEquals(50.0, t["lightness"]!!.jsonPrimitive.double, 0.0)
        // "measure" is greyed out: choosing it changes nothing
        c.chooseSetting(calibration, 16, 1)
        assertEquals("correction", c.settingValue(c.state.value, "channelmixerrgb", "channelmixerrgb/area mode")!!.content)
        val p = c.state.value.modules.first { it.key == calibration }.params
        assertEquals("DT_ILLUMINANT_CUSTOM", (p["illuminant"] as JsonPrimitive).content)
    }

    @Test fun undoKeepsThePickerArmedAndReadsTheValuesAgain() = runBlocking {
        val (c, e) = controller()
        arm(c, wb, 4)
        val picks = e.picks.size
        c.undo()
        assertTrue(c.pickerArmed(c.state.value, wb, 4))
        waitFor("read again") { e.picks.size > picks }
        assertTrue(e.picks.last().first == null)
    }

    // ------------------------------------------------------------------ deliverable 5

    @Test fun theInputProfileListComesFromTheEngineAndWritesTheChosenProfile() = runBlocking {
        val (c, e) = controller()
        val colorin = InstanceKey("colorin", 0)
        val model = TestSetup.schema.modules.getValue("colorin").actionLists.getValue(0)
        waitFor("list read") { c.state.value.actionLists[colorin to 0] != null }
        val entries = model.entries(c.state.value.actionLists[colorin to 0])
        // the stand-in lists four input and three working profiles: the list shows the input ones
        assertEquals(4, entries.size)
        val p0 = (c.state.value.modules.first { it.key == colorin }.params["type"] as JsonPrimitive).content
        assertEquals(p0, entries[model.currentIndex(entries)]["type"]!!.jsonPrimitive.content)
        val n0 = c.state.value.history.items.size
        val pick = entries.indexOfFirst { it["type"]!!.jsonPrimitive.content != p0 }
        c.chooseActionList(colorin, 0, pick)
        // one params_set (the stand-in may merge it into colorin's item of the default edit)
        assertTrue(c.state.value.history.items.size in n0..n0 + 1)
        val p1 = (c.state.value.modules.first { it.key == colorin }.params["type"] as JsonPrimitive).content
        assertEquals(entries[pick]["type"]!!.jsonPrimitive.content, p1)
        // read again for the new parameters: the chosen entry is the current one
        val again = model.entries(c.state.value.actionLists[colorin to 0])
        assertEquals(pick, model.currentIndex(again))
        assertTrue(e.changes().none { it.first == "colorin" })
    }

    @Test fun denoiseProfilesAndFineTuningFollowTheEngine() = runBlocking {
        val (c, e) = controller()
        val denoise = InstanceKey("denoiseprofile", 0)
        waitFor("list read") { c.state.value.actionLists[denoise to 0] != null }
        // the stand-in has no noise profiles: the list is empty (shown greyed out)
        assertTrue(TestSetup.schema.modules.getValue("denoiseprofile").actionLists.getValue(0).entries(c.state.value.actionLists[denoise to 0]).isEmpty())
        // white balance's fine tuning: null in the stand-in (no camera preset), so the slider is not usable
        val finetune = TestSetup.schema.modules.getValue("temperature").control(10) as app.dtandroid.schema.Control.Slider
        val b = finetune.binding as Binding.Action
        assertEquals("apply_preset", b.set)
        assertEquals(mapOf("name" to "preset"), b.argsFrom)
        // brief P6a: the slider waits for the finetune range (null also for a camera preset without fine tuning,
        // where desktop hides it too) and is hidden, not greyed out, while it is null
        assertEquals("finetune_min", b.needs)
        assertTrue(b.hide)
        assertTrue(c.state.value.actionValues[wb]!!["tuning"] is kotlinx.serialization.json.JsonNull)
        assertTrue(e.changes().isEmpty())
    }
}
