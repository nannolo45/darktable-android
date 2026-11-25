// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.icons.DtIcon
import androidx.compose.ui.draw.rotate as rotateModifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.PhotoLayer
import app.dtandroid.darkroom.Viewport
import app.dtandroid.darkroom.CropToolState
import app.dtandroid.darkroom.PhotoArea
import app.dtandroid.schema.CropTool
import app.dtandroid.schema.PickSelection
import app.dtandroid.schema.PickerTool
import app.dtandroid.darkroom.PickerState
import app.dtandroid.darkroom.PhotoGeometry
import androidx.compose.foundation.layout.heightIn
import app.dtandroid.engine.api.Exif
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.ui.theme.DtTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RGBA8 from the engine to a bitmap Compose can draw. An ARGB_8888 bitmap stores its pixels as
 * R, G, B, A bytes, the engine's order, so the engine's buffer is copied into the bitmap in one
 * step without converting each pixel (brief P2b: one copy, no per-pixel loop on the main thread).
 * Alpha is 255 everywhere (engine-api.md 2.7), so premultiplication changes nothing.
 */
fun RenderedImage.toImageBitmap(): ImageBitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
    return bmp.asImageBitmap()
}

private class Converted(val layer: PhotoLayer, val bitmap: ImageBitmap)

@Composable
private fun rememberLayer(layer: PhotoLayer?): Converted? = remember(layer) { layer?.let { Converted(it, it.image.toImageBitmap()) } }

private const val MAX_ZOOM_OVER_100 = 4f
/** brief P3h: the handle's drawing is for a vertical divider; turned for a horizontal one */
private const val SPLIT_HANDLE_HORIZONTAL_DEGREES = 90f
private const val TWO_FINGER_TAP_MS = 300L
private const val TAP_ZOOM_TOLERANCE = 0.05f

/**
 * The photo on the mid-grey surround: pinch to zoom, drag to pan, double tap
 * toggles fit and 100 %, and on phones a two-finger tap is undo (ui-spec.md 4).
 *
 * Brief P3a: with the crop tool open the photo is the uncropped picture with the frame over it (ui-spec.md 6):
 * a one-finger drag that starts on a handle or inside the frame belongs to the frame; a drag that starts away
 * from it, or two fingers, pan and zoom as before.
 */
@Composable
fun PhotoView(controller: DarkroomController, s: DarkroomState, inset: Dp, twoFingerUndo: Boolean, modifier: Modifier = Modifier,
              /** brief PUI3a: the colour around the photo; null: darktable's surround (the l1 layout puts the photo on black) */
              surround: androidx.compose.ui.graphics.Color? = null) {
    val c = DtTheme.colors
    val preview = rememberLayer(s.preview)
    val region = rememberLayer(s.region)
    val before = rememberLayer(s.beforePreview)
    // brief P2f: the best picture already there, while the photo opens or renders
    val placeholderImage = s.placeholder
    val placeholder = remember(placeholderImage) { placeholderImage?.toImageBitmap() }
    val info = s.info
    // brief P3a: the crop tool's uncropped picture and its size (the frame's fractions refer to it)
    val tool = s.cropTool?.takeIf { !s.before && it.picture != null }
    val toolPicture = rememberLayer(tool?.picture)
    val toolOpen = tool != null
    var size by remember { mutableStateOf(IntSize.Zero) }
    // the view goes back to fit when the tool opens or closes: the picture changes its size
    var zoom by remember(s.index, toolOpen) { mutableFloatStateOf(1f) }
    var cx by remember(s.index, toolOpen) { mutableFloatStateOf(0.5f) }
    var cy by remember(s.index, toolOpen) { mutableFloatStateOf(0.5f) }
    val iw = tool?.inW ?: info?.width ?: placeholderImage?.width ?: 1
    val ih = tool?.inH ?: info?.height ?: placeholderImage?.height ?: 1
    val fit = if (size.width > 0) min(size.width.toFloat() / iw, size.height.toFloat() / ih) else 1f
    val zoom100 = max(1f, 1f / fit)
    val currentFit by rememberUpdatedState(fit)
    val currentTool by rememberUpdatedState(tool)
    // brief P3c: the armed colour picker (not while "before" is shown, nor with the crop tool's picture)
    val picker = s.picker?.takeIf { !s.before && tool == null }
    val pickerOn = picker != null
    val currentPicker by rememberUpdatedState(picker)
    // brief P3d: drawing a shape: one finger draws, two fingers pan and zoom (not on "before" nor the crop tool's picture)
    val ms by controller.masks.state.collectAsState()
    val drawing = ms.draw?.takeIf { !s.before && tool == null }
    val drawOn = drawing != null
    val currentDraw by rememberUpdatedState(drawing)
    // brief P3m: retouch's panel is open: its shapes over the photo; its tools and its shapes take one finger, two
    // fingers pan and zoom (not on "before", the crop tool's picture, while a mask shape is drawn or a picker is armed)
    val rtu by controller.retouch.state.collectAsState()
    val retouchOn = controller.retouch.active(s) && tool == null && !drawOn && s.picker == null
    val currentRetouch by rememberUpdatedState(retouchOn)
    val rtTool = retouchOn && rtu.tool != null
    // brief P3m: the colour look up table's picker armed: a tap picks
    val lutU by controller.lut.state.collectAsState()
    val lutPick = lutU.picking && lutU.key != null && controller.lut.wanted(s) == lutU.key && !s.before && tool == null && s.picker == null && !drawOn
    val currentLutPick by rememberUpdatedState(lutPick)
    val retouchSourceColor = c.retouchSource
    // brief P3n: rotate and perspective's panel is open: its structure over the photo; one finger is desktop's mouse
    // when there is structure (or a crop to drag), two fingers pan and zoom
    val psu by controller.perspective.state.collectAsState()
    val perspOn = controller.perspective.active(s) && tool == null && !drawOn && s.picker == null && !retouchOn
    val currentPersp by rememberUpdatedState(perspOn)
    val psTake = perspOn && psu.st != null && controller.perspective.takesFinger()
    val guideLook = guideLook()
    // brief P3e: the tone equalizer's panel is open: a vertical one-finger drag on the photo edits the band under the finger
    // (not zoomed in, so that a drag there still pans; not with a picker armed, a shape drawn or the crop tool)
    val eqKey = controller.toneEqWanted(s)?.takeIf { !s.before && tool == null && s.picker == null && !drawOn }
    val currentEq by rememberUpdatedState(eqKey)
    // brief P3h: the split view: the selected snapshot on one side of the divider, the live edit on the other
    // (not with "before" shown nor the crop tool's picture); both sides follow the same zoom and pan
    val snaps = s.snapshots
    val splitSnap = snaps.selectedSnapshot?.takeIf { !s.before && tool == null }
    val snapPreview = rememberLayer(snaps.preview)
    val snapRegion = rememberLayer(snaps.region)
    val split = snaps.split.takeIf { splitSnap != null }
    val currentSplit by rememberUpdatedState(split)
    val splitLine = c.splitLine
    val splitShadow = c.splitShadow
    val splitLineWidth = DtTheme.sizes.splitLine
    val splitHandle = DtTheme.sizes.splitHandle
    val maskLook = maskLook()
    val pickerLook = PickerLook(c.pickerFrame, c.pickerShadow, DtTheme.sizes.pickerLine, DtTheme.sizes.pickerHandle, DtTheme.sizes.pickerPoint)
    val touch = DtTheme.sizes.touch
    val cropLook = CropLook(
        c.cropDim, c.cropFrame, c.cropGrid, c.cropHandle, c.cropHandleFill, DtTheme.sizes.cropFrameLine, DtTheme.sizes.cropGridLine,
        DtTheme.sizes.cropHandle, DtTheme.sizes.cropHandleLine, DtTheme.sizes.cropSideHandleLength, DtTheme.sizes.cropSideHandleWidth,
    )

    fun clampCenter() {
        val ds = currentFit * zoom
        val halfW = size.width / (2f * ds * iw)
        val halfH = size.height / (2f * ds * ih)
        cx = if (halfW >= 0.5f) 0.5f else cx.coerceIn(halfW, 1f - halfW)
        cy = if (halfH >= 0.5f) 0.5f else cy.coerceIn(halfH, 1f - halfH)
    }

    LaunchedEffect(size, zoom, cx, cy, s.index, iw, ih) {
        if (size.width > 0) controller.setViewport(Viewport(size.width, size.height, zoom, cx, cy))
    }

    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier.background(surround ?: c.surround).padding(inset)
            .semantics { contentDescription = "photo" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { size = it }
                .onGloballyPositioned { lc ->
                    val b = lc.boundsInWindow()
                    controller.photoArea = PhotoArea(b.left, b.top, b.width, b.height)
                }
                .pointerInput(s.index, twoFingerUndo, drawOn, rtTool, psTake) {
                    if (drawOn || rtTool || psTake) return@pointerInput // brief P3d, P3m, P3n: taps place shapes, select lines
                    detectTapGestures(onDoubleTap = { p ->
                        if (zoom > 1.01f) { zoom = 1f; cx = 0.5f; cy = 0.5f } else {
                            // centre 100 % on the tapped point
                            val ds = currentFit * zoom
                            val left = size.width / 2f - cx * iw * ds
                            val top = size.height / 2f - cy * ih * ds
                            cx = ((p.x - left) / (iw * ds)).coerceIn(0f, 1f)
                            cy = ((p.y - top) / (ih * ds)).coerceIn(0f, 1f)
                            zoom = zoom100
                        }
                        clampCenter()
                    })
                }
                .pointerInput(s.index, twoFingerUndo, toolOpen, pickerOn, drawOn) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // brief P3h: a finger on the split view's divider (within half a touch target) moves it
                        val sp0 = currentSplit
                        if (sp0 != null && currentTool == null && currentDraw == null && size.width > 0 && size.height > 0) {
                            val d = if (sp0.vertical) abs(down.position.x - sp0.position * size.width) else abs(down.position.y - sp0.position * size.height)
                            if (d <= touch.toPx() / 2) {
                                down.consume()
                                do {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed) {
                                        controller.moveSplit(if (sp0.vertical) ch.position.x / size.width else ch.position.y / size.height)
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                        }
                        var maxPointers = 1
                        var moved = 0f
                        var zoomed = 1f
                        var upTime = down.uptimeMillis
                        // brief P3a: the photo's point under a finger, as fractions of the picture shown
                        fun picturePoint(p: Offset): Pair<Float, Float> =
                            PhotoGeometry.picturePoint(p.x, p.y, size.width, size.height, iw, ih, currentFit, zoom, cx, cy)
                        // brief P3a deliverable 4: "straighten" on: one finger draws the line
                        var line = false
                        val lineFrom = picturePoint(down.position)
                        if (currentTool?.straighten == true) {
                            down.consume()
                            line = true
                        }
                        // brief P3a: a touch on a handle or inside the frame belongs to the frame
                        var frame = false
                        var frameMoved = 0f
                        if (currentTool != null && !line) {
                            val ds = currentFit * zoom
                            val (px, py) = picturePoint(down.position)
                            val grab = controller.cropHit(px, py, touch.toPx() / (iw * ds), touch.toPx() / (ih * ds))
                            if (grab != CropTool.GRAB_NONE) {
                                controller.cropDragStart(grab, px, py)
                                down.consume()
                                frame = true
                            }
                        }
                        // brief P3c: with a picker armed, a touch on its area or point moves or resizes it; one
                        // finger elsewhere draws a new area once it moves (or places a point when it only taps);
                        // a second finger before that is a pinch
                        // brief P3d: drawing a shape: the first finger draws, a second one turns the touch into a pinch
                        var draw = false
                        if (currentDraw != null && currentTool == null) {
                            val (px, py) = picturePoint(down.position)
                            controller.masks.touchDown(px.toDouble(), py.toDouble())
                            down.consume()
                            draw = true
                        }
                        // brief P3m: retouch takes the finger when a tool is on or it lands on a shape; else a tap
                        // there selects, unselects or picks when the gesture ends
                        var rt = false
                        var rtLast = picturePoint(down.position)
                        var rtTap: Pair<Float, Float>? = null
                        if (currentRetouch && currentTool == null && !draw && currentPicker == null) {
                            val ds = currentFit * zoom
                            val (px, py) = rtLast
                            if (controller.retouch.touchDown(px.toDouble(), py.toDouble(), (touch.toPx() / 2 / (iw * ds)).toDouble(), (touch.toPx() / 2 / (ih * ds)).toDouble())) {
                                down.consume()
                                rt = true
                            } else rtTap = rtLast
                        } else if (currentLutPick && currentTool == null && !draw && currentPicker == null) {
                            rtTap = rtLast
                        }
                        // brief P3n: the finger goes to rotate and perspective (desktop's mouse) when it has structure
                        var ps = false
                        var psLast = picturePoint(down.position)
                        if (currentPersp && currentTool == null && !draw && !rt && currentPicker == null) {
                            val ds = currentFit * zoom
                            if (controller.perspective.touchDown(psLast.first.toDouble(), psLast.second.toDouble(), down.uptimeMillis, (touch.toPx() / 2 / (iw * ds)).toDouble())) {
                                down.consume()
                                ps = true
                            }
                        }
                        var pick = false
                        var pickPending = false
                        var pickMoved = 0f
                        val pickFrom = picturePoint(down.position)
                        if (currentPicker != null && currentTool == null && !draw) {
                            val ds = currentFit * zoom
                            val grab = controller.pickerHit(pickFrom.first, pickFrom.second, touch.toPx() / (iw * ds), touch.toPx() / (ih * ds))
                            if (grab != PickerTool.GRAB_NONE) {
                                controller.pickerDragStart(grab, pickFrom.first, pickFrom.second)
                                down.consume()
                                pick = true
                            } else pickPending = true
                        }
                        var eqPending = currentEq != null && currentTool == null && !draw && !pick && !pickPending && !frame && !line && zoom <= 1.001f
                        var eq = false
                        val eqFrom = down.position
                        do {
                            val ev = awaitPointerEvent()
                            upTime = ev.changes.maxOf { it.uptimeMillis }
                            val pressed = ev.changes.count { it.pressed }
                            if (eq) {
                                if (pressed >= 2) {
                                    controller.toneEqEnd()
                                    eq = false
                                    maxPointers = max(maxPointers, pressed)
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed) controller.toneEqMove(((ch.position.y - eqFrom.y) / size.height).toDouble())
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (eqPending) {
                                if (pressed >= 2) {
                                    eqPending = false
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed) {
                                        val dx = ch.position.x - eqFrom.x
                                        val dy = ch.position.y - eqFrom.y
                                        if (dx * dx + dy * dy > slop * slop) {
                                            eqPending = false
                                            if (abs(dy) > abs(dx)) {
                                                val (px, py) = picturePoint(eqFrom)
                                                controller.toneEqStart(currentEq!!, px, py)
                                                controller.toneEqMove((dy / size.height).toDouble())
                                                eq = true
                                                ev.changes.forEach { it.consume() }
                                                continue
                                            }
                                        }
                                    }
                                }
                            }
                            if (draw) {
                                if (pressed >= 2) {
                                    controller.masks.touchCancel()
                                    draw = false
                                    maxPointers = max(maxPointers, pressed)
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed && ch.positionChanged()) {
                                        val (px, py) = picturePoint(ch.position)
                                        controller.masks.touchMove(px.toDouble(), py.toDouble())
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (rt) {
                                if (pressed >= 2) {
                                    controller.retouch.touchCancel()
                                    rt = false
                                    maxPointers = max(maxPointers, pressed)
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed && ch.positionChanged()) {
                                        rtLast = picturePoint(ch.position)
                                        val ds = currentFit * zoom
                                        controller.retouch.touchMove(rtLast.first.toDouble(), rtLast.second.toDouble(), slop / (iw * ds).toDouble(), slop / (ih * ds).toDouble())
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (ps) {
                                if (pressed >= 2) {
                                    controller.perspective.touchCancel()
                                    ps = false
                                    maxPointers = max(maxPointers, pressed)
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed && ch.positionChanged()) {
                                        psLast = picturePoint(ch.position)
                                        val ds = currentFit * zoom
                                        controller.perspective.touchMove(psLast.first.toDouble(), psLast.second.toDouble(), ch.uptimeMillis, (slop / (iw * ds)).toDouble())
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (pick) {
                                if (pressed >= 2 && pickMoved < slop) {
                                    controller.pickerDragCancel()
                                    pick = false
                                    maxPointers = max(maxPointers, pressed)
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed && ch.positionChanged()) {
                                        pickMoved += (ch.position - ch.previousPosition).getDistance()
                                        val (px, py) = picturePoint(ch.position)
                                        controller.pickerDragMove(px, py)
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (pickPending) {
                                if (pressed >= 2) {
                                    pickPending = false
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed) {
                                        pickMoved = (ch.position - down.position).getDistance()
                                        if (pickMoved > slop) {
                                            // a drag away from the selection: a new area (a point for point pickers)
                                            controller.pickerDragStart(PickerTool.GRAB_NONE, pickFrom.first, pickFrom.second)
                                            val (px, py) = picturePoint(ch.position)
                                            controller.pickerDragMove(px, py)
                                            pick = true
                                            pickPending = false
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (line) {
                                if (pressed >= 2) {
                                    // two fingers: pinch and pan, no line
                                    controller.cropStraighten(true)
                                    line = false
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed) {
                                        val (px, py) = picturePoint(ch.position)
                                        controller.cropLine(lineFrom.first, lineFrom.second, px, py)
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            if (frame) {
                                if (pressed >= 2 && frameMoved < slop) {
                                    // a second finger before the frame moved: a pinch, the frame goes back
                                    controller.cropDragCancel()
                                    frame = false
                                } else {
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch != null && ch.pressed && ch.positionChanged()) {
                                        frameMoved += (ch.position - ch.previousPosition).getDistance()
                                        val (px, py) = picturePoint(ch.position)
                                        controller.cropDragMove(px, py)
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                            }
                            maxPointers = max(maxPointers, pressed)
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            moved += pan.getDistance()
                            zoomed *= z
                            if (maxPointers >= 2 || zoom > 1.001f) {
                                if (z != 1f) zoom = (zoom * z).coerceIn(1f, zoom100 * MAX_ZOOM_OVER_100)
                                if (pan != Offset.Zero && zoom > 1f) {
                                    val ds = currentFit * zoom
                                    cx -= pan.x / (ds * iw)
                                    cy -= pan.y / (ds * ih)
                                }
                                clampCenter()
                                if (z != 1f || (pan != Offset.Zero && zoom > 1f)) ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (eq) { controller.toneEqEnd(); return@awaitEachGesture }
                        if (draw) { controller.masks.touchUp(); return@awaitEachGesture }
                        if (ps) { controller.perspective.touchUp(psLast.first.toDouble(), psLast.second.toDouble(), upTime); return@awaitEachGesture }
                        if (rt) {
                            val ds = currentFit * zoom
                            controller.retouch.touchUp(rtLast.first.toDouble(), rtLast.second.toDouble(), (touch.toPx() / 2 / (iw * ds)).toDouble(), (touch.toPx() / 2 / (ih * ds)).toDouble())
                            return@awaitEachGesture
                        }
                        val tapAt = rtTap
                        if (tapAt != null && maxPointers == 1 && moved < slop) {
                            val ds = currentFit * zoom
                            if (currentLutPick) controller.lut.tap(tapAt.first.toDouble(), tapAt.second.toDouble())
                            else controller.retouch.tap(tapAt.first.toDouble(), tapAt.second.toDouble(), (touch.toPx() / 2 / (iw * ds)).toDouble(), (touch.toPx() / 2 / (ih * ds)).toDouble())
                            return@awaitEachGesture
                        }
                        if (frame) { controller.cropDragEnd(); return@awaitEachGesture }
                        if (pick) { controller.pickerDragEnd(); return@awaitEachGesture }
                        if (pickPending) {
                            // brief P3c: one finger that did not move: a point there
                            val ds = currentFit * zoom
                            controller.pickerTap(pickFrom.first, pickFrom.second, touch.toPx() / (iw * ds), touch.toPx() / (ih * ds))
                            return@awaitEachGesture
                        }
                        if (line) {
                            val ds = currentFit * zoom
                            val w = iw * ds
                            val h = ih * ds
                            val minLength = touch.toPx()
                            controller.launch { cropLineEnd(w, h, minLength) }
                            return@awaitEachGesture
                        }
                        val dt = upTime - down.uptimeMillis
                        if (twoFingerUndo && currentDraw == null && maxPointers == 2 && moved < slop && abs(zoomed - 1f) < TAP_ZOOM_TOLERANCE && dt < TWO_FINGER_TAP_MS) {
                            controller.launch { undo() }
                        }
                    }
                },
        ) {
            val ds = fit * zoom
            val left = size.width / 2f - cx * iw * ds
            val top = size.height / 2f - cy * ih * ds
            val imgW = iw * ds
            val imgH = ih * ds
            drawRect(c.photoBackground, Offset(left, top), Size(imgW, imgH))
            val base = (if (tool != null) toolPicture else if (s.before) before else preview)?.bitmap ?: placeholder
            // brief P3a deliverable 4: while the rotation slider moves, the picture turns by the change (a positive
            // change of rotate and perspective's rotation turns the picture clockwise; see ashift.c's straightening)
            val turn = tool?.rotationLive?.let { (it - controller.rotationValue(s)).toFloat() } ?: 0f
            if (base != null) {
                // the turned picture stays inside the photo's area (the handles may reach beyond it)
                clipRect {
                    rotate(turn, Offset(left + imgW / 2, top + imgH / 2)) {
                        drawImage(
                            base,
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
            val r = region
            // brief P1b part 2: with the crop tool open, its own sharp region (not while the picture turns)
            if (!s.before && r != null && (tool != null) == s.regionOfCropTool && turn == 0f) {
                val sc = r.layer.scale
                drawImage(
                    r.bitmap,
                    dstOffset = IntOffset((left + r.layer.x / sc * ds).roundToInt(), (top + r.layer.y / sc * ds).roundToInt()),
                    dstSize = IntSize((r.layer.image.width / sc * ds).roundToInt(), (r.layer.image.height / sc * ds).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            // brief P3h: the snapshot's side, then the divider with its handle
            if (split != null) {
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val cut = if (split.vertical) split.position * w else split.position * h
                val l0 = if (split.vertical && !split.snapshotFirst) cut else 0f
                val t0 = if (!split.vertical && !split.snapshotFirst) cut else 0f
                val r0 = if (split.vertical && split.snapshotFirst) cut else w
                val b0 = if (!split.vertical && split.snapshotFirst) cut else h
                clipRect(l0, t0, r0, b0) {
                    drawRect(c.photoBackground, Offset(left, top), Size(imgW, imgH))
                    snapPreview?.let { sp ->
                        drawImage(sp.bitmap, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                    snapRegion?.let { sr ->
                        val sc = sr.layer.scale
                        drawImage(
                            sr.bitmap,
                            dstOffset = IntOffset((left + sr.layer.x / sc * ds).roundToInt(), (top + sr.layer.y / sc * ds).roundToInt()),
                            dstSize = IntSize((sr.layer.image.width / sc * ds).roundToInt(), (sr.layer.image.height / sc * ds).roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
                // the line: light over a dark edge (the handle is the ui-icons splitHandle, drawn over the canvas)
                val lw = splitLineWidth.toPx()
                for ((col, t) in listOf(splitShadow to lw * 3, splitLine to lw)) {
                    if (split.vertical) drawRect(col, Offset(cut - t / 2, 0f), Size(t, h))
                    else drawRect(col, Offset(0f, cut - t / 2), Size(w, t))
                }
            }
            if (tool != null) drawCropFrame(tool.clip, left, top, imgW, imgH, cropLook)
            // brief P3c: the armed picker's area or point
            picker?.selection?.let { sel -> drawPickerSelection(sel, left, top, imgW, imgH, pickerLook) }
            // brief P3d: the shape being drawn, or the shape tapped in the masks tab
            if (drawing != null) drawDrawTool(drawing, left, top, imgW, imgH, maskLook)
            else if (!s.before && tool == null && s.view == app.dtandroid.darkroom.BottomView.MASKS) ms.outline?.let { drawMaskOutline(it, left, top, imgW, imgH, maskLook) }
            // brief P3m: retouch's shapes, sources and what is being placed
            // brief P4c: the edit's frame (crop's box) over the whole picture as a guide, as desktop's "expose cropper"
            if (retouchOn) rtu.focus?.frame?.let { drawFocusFrame(it, left, top, imgW, imgH, guideLook) }
            if (retouchOn) drawRetouch(rtu, left, top, imgW, imgH, maskLook, retouchSourceColor)
            // brief P3n: rotate and perspective's crop outline, lines and corners
            if (perspOn) drawPerspective(psu, left, top, imgW, imgH, guideLook)
            tool?.line?.let { l -> drawStraightenLine(left + l[0] * imgW, top + l[1] * imgH, left + l[2] * imgW, top + l[3] * imgH, cropLook) }
        }
        // brief P2i: at fit nothing is said after a change; zoomed in, "rendering…" while the sharp region comes
        val note = s.working ?: if (s.regionPending && zoom > DarkroomController.ZOOM_FIT_LIMIT && !s.before) DarkroomController.RENDERING else null
        note?.let { WorkingNote(it, if (s.working != null) s.workingProgress else null, Modifier.align(Alignment.BottomStart)) }
        // brief P3h: the divider's handle (centred on the line; the touch target is the line's whole length)
        if (split != null && size.width > 0) {
            val hp = with(androidx.compose.ui.platform.LocalDensity.current) { splitHandle.roundToPx() }
            val cut = if (split.vertical) split.position * size.width else split.position * size.height
            val hx = if (split.vertical) cut - hp / 2f else size.width / 2f - hp / 2f
            val hy = if (split.vertical) size.height / 2f - hp / 2f else cut - hp / 2f
            Box(
                Modifier.align(Alignment.TopStart).offset { IntOffset(hx.roundToInt(), hy.roundToInt()) }
                    .size(splitHandle).clip(CircleShape).background(splitShadow).border(splitLineWidth, splitLine, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DtIconImage(DtIcon.SplitHandle, null, splitLine, splitHandle,
                    if (split.vertical) Modifier else Modifier.rotateModifier(SPLIT_HANDLE_HORIZONTAL_DEGREES))
            }
        }
        // brief P3h: which side is which (mockup B3-Snapshots: the snapshot's name and "current" in the corners)
        if (split != null && splitSnap != null && note == null) {
            val snapAt = when {
                split.vertical && split.snapshotFirst -> Alignment.BottomStart
                split.vertical -> Alignment.BottomEnd
                split.snapshotFirst -> Alignment.TopStart
                else -> Alignment.BottomStart
            }
            val liveAt = when {
                split.vertical && split.snapshotFirst -> Alignment.BottomEnd
                split.vertical -> Alignment.BottomStart
                split.snapshotFirst -> Alignment.BottomStart
                else -> Alignment.TopStart
            }
            SplitLabel("snapshot · ${splitSnap.name}", Modifier.align(snapAt))
            SplitLabel("current", Modifier.align(liveAt))
        }
    }
}

/** Brief P3h: a side's name over the photo in the split view. */
@Composable
private fun SplitLabel(text: String, modifier: Modifier) {
    val c = DtTheme.colors
    DtText(text, DtTheme.type.small, c.textBright,
        modifier.padding(DtTheme.space.m).background(c.scrim).padding(horizontal = DtTheme.space.s, vertical = DtTheme.space.xxs)
            .semantics { contentDescription = text })
}

/**
 * Brief P3a: what the crop tool says in the line under the photo (phone) or in the top bar (tablet), as the mockup
 * has it: the cropped picture's size, as desktop shows while dragging. Not over the photo: it would hide the handles.
 */
fun cropNote(t: CropToolState): String {
    val (w, h) = t.outputSize()
    return "crop · $w × $h"
}

/**
 * Brief P3c: what the line under the photo says while a picker is armed: the picked values as desktop's global colour
 * picker shows them, or why there are none.
 */
fun pickerNote(p: PickerState): List<String> = when {
    p.outside -> listOf("picker · the selection is outside the picture: tap or drag on the photo")
    p.dragging -> listOf("picker · lift the finger to pick")
    p.readout.isEmpty() -> listOf("picker · picking…")
    else -> p.readout
}

/** Brief P3c: the colour picker's colours and sizes, from the tokens. */
class PickerLook(
    val frame: androidx.compose.ui.graphics.Color, val shadow: androidx.compose.ui.graphics.Color,
    val line: Dp, val handle: Dp, val point: Dp,
)

/**
 * Brief P3c: the picker's selection over the picture drawn at [left], [top], [w] x [h]: an area as a light frame
 * over a dark outline with square handles on its corners (it can be moved and resized), a point as a cross with a
 * small square in its middle.
 */
fun DrawScope.drawPickerSelection(sel: PickSelection, left: Float, top: Float, w: Float, h: Float, look: PickerLook) {
    val l = look.line.toPx()
    val area = sel.area
    if (area != null) {
        val x0 = left + area[0].toFloat() * w
        val y0 = top + area[1].toFloat() * h
        val x1 = left + area[2].toFloat() * w
        val y1 = top + area[3].toFloat() * h
        drawRect(look.shadow, Offset(x0 - l, y0 - l), Size(x1 - x0 + 2 * l, y1 - y0 + 2 * l), style = Stroke(l * 3))
        drawRect(look.frame, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(l))
        val hs = look.handle.toPx()
        for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) {
            drawRect(look.shadow, Offset(x - hs / 2, y - hs / 2), Size(hs, hs))
            drawRect(look.frame, Offset(x - hs / 2 + l, y - hs / 2 + l), Size(hs - 2 * l, hs - 2 * l), style = Stroke(l))
        }
        return
    }
    val p = sel.point ?: return
    val x = left + p[0].toFloat() * w
    val y = top + p[1].toFloat() * h
    val r = look.point.toPx()
    for ((col, t) in listOf(look.shadow to l * 3, look.frame to l)) {
        drawRect(col, Offset(x - r, y - t / 2), Size(2 * r, t))
        drawRect(col, Offset(x - t / 2, y - r), Size(t, 2 * r))
    }
    val hs = look.handle.toPx() / 2
    drawRect(look.shadow, Offset(x - hs, y - hs), Size(2 * hs, 2 * hs), style = Stroke(l * 3))
    drawRect(look.frame, Offset(x - hs, y - hs), Size(2 * hs, 2 * hs), style = Stroke(l))
}

/** Brief P3a: the crop tool's colours and sizes, from the tokens (ui-spec.md 6). */
class CropLook(
    val dim: androidx.compose.ui.graphics.Color, val frame: androidx.compose.ui.graphics.Color, val grid: androidx.compose.ui.graphics.Color,
    val handle: androidx.compose.ui.graphics.Color, val handleFill: androidx.compose.ui.graphics.Color,
    val frameLine: Dp, val gridLine: Dp, val handleSize: Dp, val handleLine: Dp, val sideLength: Dp, val sideWidth: Dp,
)

/**
 * Brief P3a: the frame [g] over the picture drawn at [left], [top], [w] x [h]: the picture outside the frame dimmed,
 * the frame, a rule-of-thirds grid inside it, square handles on the corners and bars on the sides' middles.
 */
fun DrawScope.drawCropFrame(g: CropTool.Clip, left: Float, top: Float, w: Float, h: Float, look: CropLook) {
    val fx = left + g.x * w
    val fy = top + g.y * h
    val fw = g.w * w
    val fh = g.h * h
    val right = left + w
    val bottom = top + h
    // outside the frame: four bands
    drawRect(look.dim, Offset(left, top), Size(w, fy - top))
    drawRect(look.dim, Offset(left, fy + fh), Size(w, bottom - fy - fh))
    drawRect(look.dim, Offset(left, fy), Size(fx - left, fh))
    drawRect(look.dim, Offset(fx + fw, fy), Size(right - fx - fw, fh))
    // the rule of thirds
    val gl = look.gridLine.toPx()
    for (i in 1 until THIRDS) {
        drawRect(look.grid, Offset(fx + fw * i / THIRDS - gl / 2, fy), Size(gl, fh))
        drawRect(look.grid, Offset(fx, fy + fh * i / THIRDS - gl / 2), Size(fw, gl))
    }
    val fl = look.frameLine.toPx()
    drawRect(look.frame, Offset(fx, fy), Size(fw, fh), style = Stroke(fl))
    // handles: corners as squares centred on the corners, sides as bars centred on the sides
    val hs = look.handleSize.toPx()
    val hl = look.handleLine.toPx()
    fun handle(x: Float, y: Float, hw: Float, hh: Float) {
        drawRect(look.handleFill, Offset(x - hw / 2, y - hh / 2), Size(hw, hh))
        drawRect(look.handle, Offset(x - hw / 2 + hl / 2, y - hh / 2 + hl / 2), Size(hw - hl, hh - hl), style = Stroke(hl))
    }
    for (x in listOf(fx, fx + fw)) for (y in listOf(fy, fy + fh)) handle(x, y, hs, hs)
    val sl = look.sideLength.toPx()
    val sw = look.sideWidth.toPx()
    handle(fx + fw / 2, fy, sl, sw)
    handle(fx + fw / 2, fy + fh, sl, sw)
    handle(fx, fy + fh / 2, sw, sl)
    handle(fx + fw, fy + fh / 2, sw, sl)
}

private const val THIRDS = 3

/** Brief P3a deliverable 4: the straightening line being drawn, from ([x0], [y0]) to ([x1], [y1]) on the screen. */
fun DrawScope.drawStraightenLine(x0: Float, y0: Float, x1: Float, y1: Float, look: CropLook) {
    val dx = x1 - x0
    val dy = y1 - y0
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
    val t = look.handleLine.toPx()
    // a thin bar turned along the line (the screen rules keep lines to rectangles)
    rotate(angle, Offset(x0, y0)) {
        drawRect(look.handleFill, Offset(x0, y0 - t), Size(length, t * 2))
        drawRect(look.frame, Offset(x0, y0 - t / 2), Size(length, t))
    }
}

/**
 * Brief P3a deliverable 4: under the photo while the crop tool is open: rotate and perspective's rotation as
 * darktable's slider (drag, double tap on the bar or the name resets, long press for exact entry), and
 * "straighten": then a line drawn on the photo along a horizon or a wall sets the rotation as desktop's
 * right-button drag does.
 */
@Composable
fun CropRotationRow(controller: DarkroomController, s: DarkroomState, modifier: Modifier = Modifier) {
    val t = s.cropTool ?: return
    val sl = controller.rotationSlider() ?: return
    val key = controller.rotationKey(s) ?: return
    val value = t.rotationLive ?: controller.rotationValue(s)
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SliderControl(
                label = sl.label ?: "rotation",
                slider = sl,
                value = value,
                enabled = !s.readOnly,
                onDrag = { controller.cropRotationDrag(it) },
                onDragEnd = { controller.cropRotationDragEnd() },
                onReset = { controller.launch { cropRotate(sl.default) } },
                onExact = { controller.setOverlay(app.dtandroid.darkroom.Overlay.NumberEntry(key, sl.id)) },
            )
        }
        DtTextButton("straighten", { controller.cropStraighten(!t.straighten) }, selected = t.straighten, compact = true,
            modifier = Modifier.padding(start = DtTheme.space.m)
                .semantics { contentDescription = "straighten: draw a line along a horizon or a wall" })
    }
}

/**
 * Brief P2f: a quiet line over the bottom of the photo saying what is happening ("opening…",
 * "downloading… 40 %", "rendering…"), with a thin bar when the share done is known.
 */
@Composable
fun WorkingNote(text: String, progress: Float?, modifier: Modifier = Modifier) {
    val c = DtTheme.colors
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().background(c.scrim)
        .semantics { contentDescription = text }) {
        DtText(text, DtTheme.type.small, c.textBright, Modifier.padding(horizontal = DtTheme.space.m, vertical = DtTheme.space.xs))
        if (progress != null) ProgressLine(progress)
    }
}

/** A thin progress bar in the slider's colours (brief P2f: downloads and syncs). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val c = DtTheme.colors
    Box(modifier.fillMaxWidth().height(DtTheme.sizes.progressLine).background(c.sliderTrack)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(DtTheme.sizes.progressLine).background(c.sliderFill))
    }
}

/** One line: exposure time, aperture, focal length, ISO, camera. */
fun exifLine(e: Exif, withCamera: Boolean = true): String {
    val parts = ArrayList<String>()
    e.exposure?.let { t -> parts += if (t > 0 && t < 1) "1/${(1 / t).roundToInt()}" else String.format(Locale.ROOT, "%.1f s", t) }
    e.aperture?.let { parts += String.format(Locale.ROOT, "f/%.1f", it) }
    e.focalLength?.let { parts += "${it.roundToInt()} mm" }
    e.iso?.let { parts += "ISO ${it.roundToInt()}" }
    if (withCamera) cameraName(e).takeIf { it.isNotEmpty() }?.let { parts += it }
    return parts.joinToString(" · ")
}

fun cameraName(e: Exif) = listOf(e.maker, e.model).filter { it.isNotBlank() }.joinToString(" ")

@Composable
fun InfoLine(s: DarkroomState) {
    // brief P3c deliverable 4: with a picker armed, the picked values (one line, or mean, min and max for an area)
    s.picker?.let { p ->
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.infoLine).semantics { contentDescription = "picked values" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            val lines = pickerNote(p)
            for (l in lines) DtText(l, DtTheme.type.small, DtTheme.colors.text)
        }
        return
    }
    Box(Modifier.fillMaxWidth().height(DtTheme.sizes.infoLine), contentAlignment = Alignment.Center) {
        // brief P3a: with the crop tool open, the size the cropped picture will have
        val text = s.cropTool?.let { cropNote(it) } ?: s.info?.exif?.let { exifLine(it) } ?: ""
        DtText(text, DtTheme.type.small, if (s.cropTool != null) DtTheme.colors.text else DtTheme.colors.textMuted)
    }
}

/** The series in the caller's order; tap opens a photo. Strip versions: a badge on a photo with versions, tapped, shows them. */
@Composable
fun Filmstrip(controller: DarkroomController, s: DarkroomState, height: Dp, thumbWidth: Dp, thumbHeight: Dp, centered: Boolean) {
    val c = DtTheme.colors
    LazyRow(
        Modifier.fillMaxWidth().height(height).background(c.filmstrip),
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DtTheme.space.m),
    ) {
        itemsIndexed(s.series.items) { i, item ->
            val thumb = s.thumbnails[i]
            val bmp = remember(thumb) { thumb?.toImageBitmap() }
            val versions = s.stripVersions[i]
            val expanded = versions != null && s.stripExpanded == i
            Row(
                Modifier.then(if (expanded) Modifier.background(c.panel) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(thumbWidth, thumbHeight)
                        .border(DtTheme.sizes.thumbRing, if (i == s.index) c.textBright else c.filmstrip)
                        .padding(DtTheme.sizes.thumbRing)
                        .background(c.photoBackground)
                        .clickable(role = Role.Image) { if (i != s.index) controller.launch { openPhoto(i) } }
                        .semantics { contentDescription = "Photo ${i + 1} of ${s.series.items.size}, ${item.fileName}" },
                ) {
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    // brief P2e: a photo not on the phone yet (or without a thumbnail) shows its name
                    else DtText(item.fileName, DtTheme.type.tiny, c.textMuted, Modifier.align(Alignment.Center).padding(DtTheme.space.xs), maxLines = 3)
                    if (versions != null) {
                        // strip versions: how many versions, and which one is open when it is not the original
                        val label = if (item.version > 0) "v${item.version}·${versions.size}" else "${versions.size}"
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .background(if (expanded) c.accent else c.scrim)
                                .clickable(role = Role.Button) { controller.toggleStripVersions(i) }
                                .padding(horizontal = DtTheme.space.xxs)
                                .semantics {
                                    contentDescription = "${versions.size} versions of ${item.fileName}"
                                    stateDescription = if (expanded) "expanded" else "collapsed"
                                },
                        ) { DtText(label, DtTheme.type.tiny, if (expanded) c.bg else c.textBright) }
                    }
                }
                if (expanded && versions != null) {
                    for (v in versions) {
                        val open = i == s.index && v.n == item.version
                        // the open version's picture is the photo's own thumbnail when none was kept yet
                        val pic = v.thumb ?: if (v.n == item.version) thumb else null
                        val vb = remember(pic) { pic?.toImageBitmap() }
                        Box(
                            Modifier.size(thumbWidth, thumbHeight)
                                .border(DtTheme.sizes.thumbRing, if (open) c.accent else c.panel)
                                .padding(DtTheme.sizes.thumbRing)
                                .background(c.photoBackground)
                                .clickable(role = Role.Image) { if (!open) controller.launch { openStripVersion(i, v.n) } }
                                .semantics { contentDescription = "Version ${v.n}${if (v.name.isNotBlank()) ", ${v.name}" else ""} of ${item.fileName}" },
                        ) {
                            if (vb != null) Image(vb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            DtText(if (v.n == 0) "orig" else "v${v.n}", DtTheme.type.tiny, c.textBright,
                                Modifier.align(Alignment.BottomStart).background(c.scrim).padding(horizontal = DtTheme.space.xxs), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
