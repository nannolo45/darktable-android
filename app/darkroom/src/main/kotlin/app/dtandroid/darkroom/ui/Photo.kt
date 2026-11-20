// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
fun PhotoView(controller: DarkroomController, s: DarkroomState, inset: Dp, twoFingerUndo: Boolean, modifier: Modifier = Modifier) {
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
        modifier.background(c.surround).padding(inset)
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
                .pointerInput(s.index, twoFingerUndo) {
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
                .pointerInput(s.index, twoFingerUndo, toolOpen) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var maxPointers = 1
                        var moved = 0f
                        var zoomed = 1f
                        var upTime = down.uptimeMillis
                        // brief P3a: the photo's point under a finger, as fractions of the picture shown
                        fun picturePoint(p: Offset): Pair<Float, Float> {
                            val ds = currentFit * zoom
                            val left = size.width / 2f - cx * iw * ds
                            val top = size.height / 2f - cy * ih * ds
                            return ((p.x - left) / (iw * ds)) to ((p.y - top) / (ih * ds))
                        }
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
                        do {
                            val ev = awaitPointerEvent()
                            upTime = ev.changes.maxOf { it.uptimeMillis }
                            val pressed = ev.changes.count { it.pressed }
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
                        if (frame) { controller.cropDragEnd(); return@awaitEachGesture }
                        if (line) {
                            val ds = currentFit * zoom
                            val w = iw * ds
                            val h = ih * ds
                            val minLength = touch.toPx()
                            controller.launch { cropLineEnd(w, h, minLength) }
                            return@awaitEachGesture
                        }
                        val dt = upTime - down.uptimeMillis
                        if (twoFingerUndo && maxPointers == 2 && moved < slop && abs(zoomed - 1f) < TAP_ZOOM_TOLERANCE && dt < TWO_FINGER_TAP_MS) {
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
                rotate(turn, Offset(left + imgW / 2, top + imgH / 2)) {
                    drawImage(
                        base,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            val r = region
            if (!s.before && r != null && tool == null) {
                val sc = r.layer.scale
                drawImage(
                    r.bitmap,
                    dstOffset = IntOffset((left + r.layer.x / sc * ds).roundToInt(), (top + r.layer.y / sc * ds).roundToInt()),
                    dstSize = IntSize((r.layer.image.width / sc * ds).roundToInt(), (r.layer.image.height / sc * ds).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (tool != null) drawCropFrame(tool.clip, left, top, imgW, imgH, cropLook)
            tool?.line?.let { l -> drawStraightenLine(left + l[0] * imgW, top + l[1] * imgH, left + l[2] * imgW, top + l[3] * imgH, cropLook) }
        }
        // brief P2i: at fit nothing is said after a change; zoomed in, "rendering…" while the sharp region comes
        val note = s.working ?: if (s.regionPending && zoom > DarkroomController.ZOOM_FIT_LIMIT && !s.before) DarkroomController.RENDERING else null
        note?.let { WorkingNote(it, if (s.working != null) s.workingProgress else null, Modifier.align(Alignment.BottomStart)) }
    }
}

/**
 * Brief P3a: what the crop tool says in the line under the photo (phone) or in the top bar (tablet), as the mockup
 * has it: the cropped picture's size, as desktop shows while dragging. Not over the photo: it would hide the handles.
 */
fun cropNote(t: CropToolState): String {
    val (w, h) = t.outputSize()
    return "crop · $w × $h"
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
    Box(Modifier.fillMaxWidth().height(DtTheme.sizes.infoLine), contentAlignment = Alignment.Center) {
        // brief P3a: with the crop tool open, the size the cropped picture will have
        val text = s.cropTool?.let { cropNote(it) } ?: s.info?.exif?.let { exifLine(it) } ?: ""
        DtText(text, DtTheme.type.small, if (s.cropTool != null) DtTheme.colors.text else DtTheme.colors.textMuted)
    }
}

/** The series in the caller's order; tap opens a photo. */
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
            }
        }
    }
}
