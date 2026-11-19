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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
 */
@Composable
fun PhotoView(controller: DarkroomController, s: DarkroomState, inset: Dp, twoFingerUndo: Boolean, modifier: Modifier = Modifier) {
    val c = DtTheme.colors
    val preview = rememberLayer(s.preview)
    val region = rememberLayer(s.region)
    val before = rememberLayer(s.beforePreview)
    val info = s.info
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(s.index) { mutableFloatStateOf(1f) }
    var cx by remember(s.index) { mutableFloatStateOf(0.5f) }
    var cy by remember(s.index) { mutableFloatStateOf(0.5f) }
    val iw = info?.width ?: 1
    val ih = info?.height ?: 1
    val fit = if (size.width > 0) min(size.width.toFloat() / iw, size.height.toFloat() / ih) else 1f
    val zoom100 = max(1f, 1f / fit)
    val currentFit by rememberUpdatedState(fit)

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
                .pointerInput(s.index, twoFingerUndo) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var maxPointers = 1
                        var moved = 0f
                        var zoomed = 1f
                        var upTime = down.uptimeMillis
                        do {
                            val ev = awaitPointerEvent()
                            upTime = ev.changes.maxOf { it.uptimeMillis }
                            maxPointers = max(maxPointers, ev.changes.count { it.pressed })
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
            val base = if (s.before) before else preview
            if (base != null) {
                drawImage(
                    base.bitmap,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            val r = region
            if (!s.before && r != null) {
                val sc = r.layer.scale
                drawImage(
                    r.bitmap,
                    dstOffset = IntOffset((left + r.layer.x / sc * ds).roundToInt(), (top + r.layer.y / sc * ds).roundToInt()),
                    dstSize = IntSize((r.layer.image.width / sc * ds).roundToInt(), (r.layer.image.height / sc * ds).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
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
        DtText(s.info?.exif?.let { exifLine(it) } ?: "", DtTheme.type.small, DtTheme.colors.textMuted)
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
            }
        }
    }
}
