// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Paint
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.DrawTool
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.MaskMode
import app.dtandroid.darkroom.MaskSheet
import app.dtandroid.darkroom.MaskMemberState
import app.dtandroid.darkroom.ModuleMask
import app.dtandroid.darkroom.MaskShape
import app.dtandroid.darkroom.Masks
import app.dtandroid.darkroom.MasksState
import app.dtandroid.darkroom.ShapeOutline
import app.dtandroid.darkroom.ShapeType
import app.dtandroid.darkroom.key
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Brief P3d (stage 1): the masks tab, the drawing tools' panel, their sheets, the mask indicator of a module header,
 * and the shapes drawn over the photo (ui-spec.md section "masks"; mockup docs/design/mockups/P3d-Masks.html with
 * the owner's changes: the mask controls live in the masks tab, a module only shows that it has a mask).
 */

fun shapeIcon(type: String): DtIcon = when (type) {
    "brush" -> DtIcon.MaskBrush
    "circle" -> DtIcon.MaskCircle
    "ellipse" -> DtIcon.MaskEllipse
    "gradient" -> DtIcon.MaskGradient
    "path" -> DtIcon.MaskPath
    else -> DtIcon.Mask
}

private fun moduleOf(s: DarkroomState, key: InstanceKey?): ModuleInstance? = key?.let { k -> s.modules.firstOrNull { it.key == k } }

/** A mask slider of the tab or the tool panel: [factor] turns the value into what is shown (0.05 -> "5 %"). */
internal fun maskSlider(id: Int, label: String, min: Double, max: Double, default: Double, factor: Double, digits: Int, unit: String) =
    Control.Slider(
        id = id, label = label, tooltip = null, hiddenByDefault = false, binding = Binding.Param("mask.$label"),
        softMin = min, softMax = max, hardMin = min, hardMax = max, default = default, step = 1.0 / factor,
        digits = digits, factor = factor, offset = 0.0, format = unit, textDefault = null, integer = false,
    )

/** A slider that keeps its value while the finger moves and gives it at the end ([onSet]; one history item). */
@Composable
internal fun MaskSlider(slider: Control.Slider, value: Double, enabled: Boolean, live: Boolean, onSet: (Double) -> Unit) {
    var moving by remember(slider.id) { mutableStateOf<Double?>(null) }
    SliderControl(
        slider.label ?: "", slider, moving ?: value, enabled,
        onDrag = { v -> if (live) onSet(v) else moving = v },
        onDragEnd = { moving?.let { onSet(it) }; moving = null },
        onReset = { onSet(slider.default) },
        onExact = {},
    )
}

// ----------------------------------------------------------------------- the mask indicator and "mask…"

/** In a module's header whenever its mask mode is not off: a small mask icon; a tap opens the masks tab. */
@Composable
fun MaskIndicator(controller: DarkroomController, s: DarkroomState, m: ModuleInstance, name: String) {
    val mode = s.maskModes[m.key]
    if (!m.supportsBlending || !Masks.indicated(mode)) return
    val label = MaskMode.of(mode)?.label ?: (mode ?: "")
    DtIconButton(
        DtIcon.Mask, "mask of $name: $label. Open the masks tab", DtTheme.colors.text,
        onClick = { controller.openMasks(m.key) }, iconSize = DtTheme.sizes.maskIndicator,
    )
}

/** The last row of an open module that has blending: "mask…" with its mode; opens the masks tab. */
@Composable
fun MaskRow(controller: DarkroomController, s: DarkroomState, m: ModuleInstance) {
    if (!m.supportsBlending) return
    val mode = MaskMode.of(s.maskModes[m.key] ?: "off")?.label ?: (s.maskModes[m.key] ?: "")
    Box(Modifier.fillMaxWidth().background(DtTheme.colors.panel).padding(horizontal = DtTheme.space.panel)) {
        ListControl("mask…", mode, enabled = true) { controller.openMasks(m.key) }
    }
}

// ----------------------------------------------------------------------- the masks tab

@Composable
fun MasksTab(controller: DarkroomController, s: DarkroomState, ms: MasksState, modifier: Modifier = Modifier) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    val ro = s.readOnly
    Column(modifier.fillMaxWidth().background(c.panel).verticalScroll(rememberScrollState()).padding(horizontal = sp.panel, vertical = sp.xs)) {
        val inst = moduleOf(s, ms.key)
        ListControl("module", inst?.let { fullName(it) } ?: "choose…", enabled = true) { masks.openSheet(MaskSheet.Modules) }
        val m = ms.module
        when {
            inst == null || m == null -> Note("choose the module whose mask to edit")
            !m.supported -> Note("${fullName(inst)} has no blending")
            else -> ModuleMaskSection(controller, s, ms, inst, ro)
        }
        ms.note?.let { DtText(it, DtTheme.type.small, c.textStrong, Modifier.padding(vertical = sp.xs), maxLines = 3) }
        Spacer(Modifier.height(sp.m))
        Divider()
        Manager(controller, s, ms)
        Spacer(Modifier.height(sp.xxl))
    }
}

@Composable
private fun Note(text: String) = DtText(text, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.s), maxLines = 3)

@Composable
private fun ModuleMaskSection(controller: DarkroomController, s: DarkroomState, ms: MasksState, inst: ModuleInstance, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    val m = ms.module ?: return
    SheetSection("mask")
    val mode = m.mode
    for (row in listOf(listOf(MaskMode.OFF, MaskMode.UNIFORM, MaskMode.DRAWN), listOf(MaskMode.PARAMETRIC, MaskMode.BOTH))) {
        Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
            for (md in row) {
                ModeButton(md.label, mode == md, !ro && md.available, Modifier.weight(1f)) { controller.launch { masks.setMode(md) } }
            }
        }
    }
    if (mode == MaskMode.BOTH) Note("drawn & parametric: shown as the tree of its elements")
    if (mode == null) Note("this mask (${m.engineMode}) is not edited here yet")
    if (mode != null && mode != MaskMode.OFF) {
        MaskSlider(maskSlider(1, "opacity", 0.0, 100.0, 100.0, 1.0, 0, " %"), m.opacity, !ro, live = false) {
            controller.launch { masks.setOpacity(it) }
        }
        ListControl("blend mode", Masks.blendLabel(m.blendMode), enabled = !ro) { masks.openSheet(MaskSheet.BlendModes) }
    }
    if (mode == MaskMode.DRAWN) {
        DrawnShapes(controller, ms, inst, m, ro)
        ToggleControl("show mask", s.showMask == m.key, enabled = true) { masks.toggleShowMask() }
        ToggleControl("invert mask", m.invert, enabled = !ro) { controller.launch { masks.setInvert(!m.invert) } }
    }
    // brief P3d stage 2b: the parametric mode and the refinement
    if (mode == MaskMode.PARAMETRIC || mode == MaskMode.DRAWN || mode == MaskMode.BOTH) ParametricSection(controller, s, ro)
    if (mode == MaskMode.PARAMETRIC) {
        ToggleControl("show mask", s.showMask == m.key, enabled = true) { masks.toggleShowMask() }
        ToggleControl("invert mask", m.invert, enabled = !ro) { controller.launch { masks.setInvert(!m.invert) } }
    }
    if (mode != null && mode != MaskMode.OFF && mode != MaskMode.UNIFORM) RefinementSection(controller, s, ro)
}

// brief P3d fix: no early return or `continue` inside the composables below (a skipped group made the slot table
// hand a remembered lambda to the wrong call site, a ClassCastException when the shapes of a module changed)
@Composable
private fun DrawnShapes(controller: DarkroomController, ms: MasksState, inst: ModuleInstance, m: ModuleMask, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    val n = m.members.size
    DtText(if (n == 0) "no shapes in this module's mask yet" else "group \"${fullName(inst)}\" · $n ${if (n == 1) "member" else "members"}",
        DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xs))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        for (t in ShapeType.entries) {
            Column(
                Modifier.weight(1f).height(DtTheme.sizes.shapeButton).background(c.raised)
                    .clickable(enabled = !ro, role = Role.Button) { masks.startDraw(t) }
                    .semantics { contentDescription = "add ${t.key}" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                DtIconImage(shapeIcon(t.key), null, if (ro) c.textDisabled else c.textStrong, DtTheme.sizes.icon)
                DtText(t.key, DtTheme.type.small, if (ro) c.textDisabled else c.text)
            }
        }
    }
    Spacer(Modifier.height(sp.xs))
    DtTextButton("use an existing shape…", { masks.openSheet(MaskSheet.Existing) }, Modifier.fillMaxWidth(), enabled = !ro)
    // P3d 2a: the mask as a tree (MaskTree.kt) replaces stage 1's single "combine shapes" row and flat member rows
    MaskTree(controller, ms, m, ro)
}

@Composable
private fun MemberRow(controller: DarkroomController, ms: MasksState, f: MaskShape, mem: MaskMemberState, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    val name = Masks.shapeLabel(f)
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.touch).background(if (ms.selected == f.id) c.selected else c.panel)
            .clickable(role = Role.Button) { controller.launch { masks.selectShape(f.id) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtIconImage(shapeIcon(f.type), null, c.text, DtTheme.sizes.icon, Modifier.padding(end = sp.m))
        DtText(name, DtTheme.type.label, c.textStrong, Modifier.weight(1f))
        DtIconButton(DtIcon.Invert, "invert $name", if (mem.inverse) c.textBright else c.textMuted, enabled = !ro,
            onClick = { controller.launch { masks.setMemberInvert(f.id, !mem.inverse) } },
            modifier = Modifier.semantics { stateDescription = if (mem.inverse) "on" else "off" })
        DtIconButton(DtIcon.Remove, "remove $name from this module", c.text, enabled = !ro,
            onClick = { controller.launch { masks.detach(f.id) } })
    }
}

/** A mask mode: the chosen one with a ring in the accent colour (as the mockup), so it shows in both skins. */
@Composable
private fun ModeButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DtTheme.colors
    val shape = RoundedCornerShape(DtTheme.sizes.radiusControl)
    var m = modifier.height(DtTheme.sizes.touch).clip(shape).background(if (selected) c.selected else c.raised)
    if (selected) m = m.border(DtTheme.sizes.border, c.accent, shape)
    Box(
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "mask mode $label"; stateDescription = if (selected) "selected" else "not selected" },
        contentAlignment = Alignment.Center,
    ) {
        DtText(label, DtTheme.type.label, when {
            !enabled -> c.textDisabled
            selected -> c.textBright
            else -> c.textStrong
        })
    }
}

/** Desktop's mask manager: every group and shape of the photo. */
@Composable
private fun Manager(controller: DarkroomController, s: DarkroomState, ms: MasksState) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    SheetSection("shapes and groups of this photo")
    val groups = ms.forms.filter { it.isGroup }
    val inGroup = groups.flatMap { g -> g.members.map { it.id } }.toSet()
    if (groups.isEmpty() && ms.forms.isEmpty()) Note("empty until shapes are drawn")
    for (g in groups) {
        val owner = moduleOf(s, g.rootOf)
        val title = owner?.let { "group \"${fullName(it)}\"" } ?: "group \"${Masks.shapeLabel(g)}\""
        val open = g.id !in ms.folded
        Row(
            Modifier.fillMaxWidth().height(DtTheme.sizes.touch).clickable(role = Role.Button) { masks.toggleFold(g.id) }
                .semantics { stateDescription = if (open) "expanded" else "collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DtText(title, DtTheme.type.label, c.textStrong, Modifier.weight(1f))
            DtText("${g.members.size} ${if (g.members.size == 1) "shape" else "shapes"}", DtTheme.type.small, c.textMuted)
            DtIconImage(DtIcon.ChevronDown, null, c.textMuted, DtTheme.sizes.icon, Modifier.padding(start = sp.xs))
        }
        if (open) {
            if (g.members.isEmpty()) Note("no shapes")
            for (mem in g.members) ms.forms.firstOrNull { it.id == mem.id }?.let { f -> ManagerRow(controller, ms, f, g.rootOf, mem.inverse) }
        }
    }
    SheetSection("shapes not in a group")
    val loose = ms.forms.filter { !it.isGroup && it.id !in inGroup }
    if (loose.isEmpty()) Note(if (ms.forms.isEmpty()) "no shapes yet" else "every shape is in a group")
    for (f in loose) ManagerRow(controller, ms, f, null, false)
}

@Composable
private fun ManagerRow(controller: DarkroomController, ms: MasksState, f: MaskShape, from: InstanceKey?, inverse: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    val name = Masks.shapeLabel(f)
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.touch).background(if (ms.selected == f.id) c.selected else c.panel)
            .clickable(role = Role.Button) { controller.launch { masks.selectShape(f.id) } }
            .semantics { contentDescription = "shape $name"; stateDescription = if (ms.selected == f.id) "shown on the photo" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(sp.m))
        DtIconImage(shapeIcon(f.type), null, c.text, DtTheme.sizes.icon, Modifier.padding(end = sp.m))
        Column(Modifier.weight(1f)) {
            DtText(name, DtTheme.type.label, c.textStrong)
            if (f.users.size > 1) DtText("used by ${f.users.size} modules", DtTheme.type.small, c.textMuted)
        }
        if (inverse) DtIconImage(DtIcon.Invert, null, c.textMuted, DtTheme.sizes.icon)
        DtIconButton(DtIcon.More, "more, $name", c.text, onClick = { masks.openSheet(MaskSheet.More(f.id, from)) })
    }
}

// ----------------------------------------------------------------------- drawing mode

private val HINTS = mapOf(
    ShapeType.BRUSH to "drag on the photo to paint the mask",
    ShapeType.CIRCLE to "tap the photo to place the circle, drag to move it",
    ShapeType.ELLIPSE to "tap the photo to place the ellipse, drag to move it",
    ShapeType.GRADIENT to "tap the photo to place the line, drag to move it; the tinted side is below",
    ShapeType.PATH to "tap the photo to add a point",
)

/** The tool panel that replaces the bottom panel while a shape is drawn. */
@Composable
fun DrawPanel(controller: DarkroomController, s: DarkroomState, d: DrawTool, modifier: Modifier = Modifier) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    Column(modifier.fillMaxWidth().background(c.panel).padding(horizontal = sp.panel, vertical = sp.s)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            DtText("${moduleOf(s, d.key)?.let { fullName(it) } ?: d.key.op} · ${d.type.key}", DtTheme.type.body, c.textStrong)
            DtText(HINTS[d.type] ?: "", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xxs), maxLines = 2)
            val size = maskSlider(11, "size", 0.005, 0.5, 0.05, 100.0, 1, " %")
            val feather = maskSlider(12, "feather", 0.0, 0.5, 0.05, 100.0, 1, " %")
            val hardness = maskSlider(13, "hardness", 0.0, 1.0, 0.66, 100.0, 0, " %")
            val opacity = maskSlider(14, "opacity", 0.0, 1.0, 1.0, 100.0, 0, " %")
            val rotation = maskSlider(15, "rotation", -180.0, 180.0, 0.0, 1.0, 0, "°")
            val flatness = maskSlider(16, "flatness", 0.1, 1.0, 0.7, 100.0, 0, " %")
            val compression = maskSlider(17, "compression", 0.01, 1.0, 0.5, 100.0, 0, " %")
            val sliders = when (d.type) {
                ShapeType.BRUSH -> listOf(size to d.size, hardness to d.hardness, opacity to d.opacity)
                ShapeType.CIRCLE -> listOf(size to d.size, feather to d.feather)
                ShapeType.ELLIPSE -> listOf(size to d.size, feather to d.feather, rotation to d.rotation, flatness to d.flatness)
                ShapeType.GRADIENT -> listOf(rotation to d.rotation, compression to d.compression)
                ShapeType.PATH -> emptyList()
            }
            for ((sl, v) in sliders) MaskSlider(sl, v, enabled = true, live = true) { masks.setDrawValue(sl.label ?: "", it) }
            Row(Modifier.fillMaxWidth().padding(vertical = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                when (d.type) {
                    ShapeType.BRUSH -> {
                        DtTextButton("undo stroke", { masks.undoStroke() }, Modifier.weight(1f), enabled = d.strokes.isNotEmpty(), compact = true)
                        DtTextButton("show mask", { masks.toggleDrawShow() }, Modifier.weight(1f)
                            .semantics { stateDescription = if (d.showMask) "on" else "off" }, selected = d.showMask, compact = true)
                    }
                    ShapeType.PATH -> {
                        DtTextButton("remove last point", { masks.removeLastPoint() }, Modifier.weight(1f), enabled = d.points.isNotEmpty(), compact = true)
                        DtTextButton("close path", { masks.closePath() }, Modifier.weight(1f), enabled = d.points.size >= 3 && !d.closed, compact = true)
                    }
                    else -> Unit
                }
            }
            DtText("two fingers move and zoom the photo", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xxs))
        }
        Row(Modifier.fillMaxWidth().padding(top = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.m)) {
            DtTextButton("cancel", { masks.cancelDraw() }, Modifier.weight(1f))
            DtTextButton("done", { controller.launch { masks.finishDraw() } }, Modifier.weight(1f), selected = d.canFinish, enabled = d.canFinish)
        }
    }
}

// ----------------------------------------------------------------------- sheets

@Composable
fun BoxScope.MasksOverlays(controller: DarkroomController, s: DarkroomState, ms: MasksState, tablet: Boolean) {
    val sheet = ms.sheet ?: return
    val masks = controller.masks
    val close = { masks.openSheet(null) }
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    val ro = s.readOnly
    fun form(id: Int) = ms.forms.firstOrNull { it.id == id }
    when (sheet) {
        MaskSheet.Modules -> Sheet("module whose mask to edit", close, maxW) {
            for (m in masks.blendingModules()) {
                val marked = Masks.indicated(s.maskModes[m.key])
                SheetItem(fullName(m) + if (marked) " · mask" else "", enabled = true, selected = m.key == ms.key) {
                    controller.launch { masks.select(m.key) }
                }
            }
        }
        MaskSheet.BlendModes -> {
            val m = ms.module ?: return
            Sheet("blend mode", close, maxW) {
                for (b in masks.blendModes(m.colorspace)) SheetItem(Masks.blendLabel(b), !ro, selected = b == m.blendMode) {
                    controller.launch { masks.setBlendMode(b) }
                }
            }
        }
        MaskSheet.Combine -> {
            val m = ms.module ?: return
            Sheet("combine shapes", close, maxW) {
                for ((label, op) in Masks.COMBINE) SheetItem(label, !ro, selected = op == m.operator) { controller.launch { masks.setCombine(label) } }
            }
        }
        MaskSheet.Existing -> {
            val m = ms.module ?: return
            val members = m.members.map { it.id }.toSet()
            Sheet("use an existing shape", close, maxW) {
                val usable = Masks.existingOffers(ms.forms, m)
                if (usable.isEmpty()) SheetSection("no shapes in this photo yet")
                for (f in usable) {
                    val inIt = f.id in members
                    SheetItem(Masks.shapeLabel(f) + if (inIt) " · in this module" else "", enabled = !ro && !inIt, selected = inIt) {
                        controller.launch { masks.attach(f.id) }
                    }
                }
            }
        }
        is MaskSheet.More -> {
            val f = form(sheet.id) ?: return
            Sheet(Masks.shapeLabel(f), close, maxW) {
                SheetSection(if (f.users.isEmpty()) "used by no module" else "used by ${f.users.size} ${if (f.users.size == 1) "module" else "modules"}")
                SheetItem("rename", !ro) { masks.openSheet(MaskSheet.Rename(f.id)) }
                SheetItem("use in another module…", !ro) { masks.openSheet(MaskSheet.UseIn(f.id)) }
                if (!f.isGroup) SheetItem("duplicate", !ro) { controller.launch { masks.duplicate(f.id, sheet.from) } }
                // P3d 2a: tree rows know their group: wrap, group together, remove from any group
                val g = sheet.group
                if (g != null) {
                    SheetItem("wrap in group", !ro) { controller.launch { masks.wrapInGroup(g, f.id) } }
                    SheetItem("group together…", !ro && (form(g)?.members?.size ?: 0) > 1) { masks.openSheet(MaskSheet.Together(g, f.id)) }
                }
                if (sheet.from != null) SheetItem("remove from this group", !ro) { controller.launch { masks.detach(f.id, sheet.from) } }
                else if (g != null) SheetItem("remove from this group", !ro) { controller.launch { masks.removeFrom(g, f.id) } }
                SheetItem("delete shape", !ro && f.rootOf == null) { masks.openSheet(MaskSheet.Delete(f.id)) }
            }
        }
        is MaskSheet.Rename -> {
            val f = form(sheet.id) ?: return
            TextEntrySheet("rename ${Masks.shapeLabel(f)}", "name of this shape", f.name, number = false, onClose = close) { text ->
                if (text.isBlank()) "a shape needs a name" else { controller.launch { masks.rename(f.id, text) }; null }
            }
        }
        is MaskSheet.UseIn -> {
            val f = form(sheet.id) ?: return
            Sheet("use ${Masks.shapeLabel(f)} in", close, maxW) {
                for (m in masks.blendingModules()) {
                    val inIt = m.key in f.users
                    SheetItem(fullName(m) + if (inIt) " · uses it" else "", enabled = !ro && !inIt, selected = inIt) {
                        controller.launch { masks.attach(f.id, m.key) }
                    }
                }
            }
        }
        is MaskSheet.Operator, is MaskSheet.Together -> MaskTreeSheets(controller, ms, sheet, maxW, ro)
        is MaskSheet.Delete -> {
            val f = form(sheet.id) ?: return
            Sheet("delete \"${Masks.shapeLabel(f)}\" everywhere?", close, maxW) {
                SheetItem("delete", !ro) { controller.launch { masks.delete(f.id) } }
                SheetItem("cancel", true, onClick = close)
            }
        }
    }
}

// ----------------------------------------------------------------------- on the photo

/** The colours and sizes of masks over the photo, from the tokens. */
class MaskLook(val fill: Color, val line: Color, val shadow: Color, val lineWidth: Dp, val handle: Dp, val fillAlpha: Float)

@Composable
fun maskLook() = MaskLook(DtTheme.colors.maskOverlay, DtTheme.colors.maskLine, DtTheme.colors.maskShadow,
    DtTheme.sizes.maskLine, DtTheme.sizes.maskHandle, DtTheme.alpha.maskFill)

/** A straight piece from (x0, y0) to (x1, y1), [width] wide (the screen rules keep lines to rectangles). */
private fun DrawScope.segment(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Color, alpha: Float = 1f) {
    val dx = x1 - x0
    val dy = y1 - y0
    val length = sqrt(dx * dx + dy * dy)
    if (length <= 0f) return
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    rotate(angle, Offset(x0, y0)) { drawRect(color, Offset(x0, y0 - width / 2), Size(length, width), alpha = alpha) }
}

private fun DrawScope.line(pts: List<Offset>, closed: Boolean, look: MaskLook) {
    val w = look.lineWidth.toPx()
    val all = if (closed && pts.size > 2) pts + pts.first() else pts
    for (i in 1 until all.size) segment(all[i - 1].x, all[i - 1].y, all[i].x, all[i].y, w * 3, look.shadow, 0.6f)
    for (i in 1 until all.size) segment(all[i - 1].x, all[i - 1].y, all[i].x, all[i].y, w, look.line)
}

private fun DrawScope.handle(p: Offset, look: MaskLook) {
    val h = look.handle.toPx()
    drawRect(look.shadow, Offset(p.x - h / 2 - 1, p.y - h / 2 - 1), Size(h + 2, h + 2))
    drawRect(look.line, Offset(p.x - h / 2, p.y - h / 2), Size(h, h))
}

/** A shape of the photo (tapped in the list) over the picture drawn at [left], [top], [w] x [h]. */
fun DrawScope.drawMaskOutline(o: ShapeOutline, left: Float, top: Float, w: Float, h: Float, look: MaskLook) {
    val pts = o.points.map { Offset(left + it.first.toFloat() * w, top + it.second.toFloat() * h) }
    clipRect(left, top, left + w, top + h) { line(pts, o.closed, look) }
}

/** The shape being drawn, in fractions of the picture drawn at [left], [top], [w] x [h]. */
fun DrawScope.drawDrawTool(d: DrawTool, left: Float, top: Float, w: Float, h: Float, look: MaskLook) {
    val side = min(w, h)
    fun at(p: Pair<Double, Double>) = Offset(left + p.first.toFloat() * w, top + p.second.toFloat() * h)
    clipRect(left, top, left + w, top + h) {
        when (d.type) {
            ShapeType.BRUSH -> {
                val strokes = d.strokes.map { it.points to it } + listOfNotNull(d.current?.let { it to null })
                for ((points, st) in strokes) {
                    val pts = points.map { at(it) }
                    val r = ((st?.size ?: d.size) * side).toFloat()
                    if (d.showMask) {
                        // the stroke drawn opaque into a layer that is then laid over at the stroke's opacity, so
                        // its overlapping pieces do not add up
                        val a = look.fillAlpha * (0.4f + 0.6f * (st?.hardness ?: d.hardness).toFloat()) * (st?.opacity ?: d.opacity).toFloat()
                        val canvas = drawContext.canvas
                        canvas.saveLayer(Rect(left, top, left + w, top + h), Paint().apply { alpha = a })
                        for (i in 1 until pts.size) segment(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, 2 * r, look.fill)
                        for (p in pts) drawOval(look.fill, Offset(p.x - r, p.y - r), Size(2 * r, 2 * r))
                        canvas.restore()
                    } else {
                        line(pts, false, look)
                    }
                }
            }
            ShapeType.CIRCLE, ShapeType.ELLIPSE -> {
                val cpt = d.center ?: return@clipRect
                val p = at(cpt)
                val rx = (d.size * side).toFloat()
                val ry = if (d.type == ShapeType.ELLIPSE) (d.size * d.flatness * side).toFloat() else rx
                val f = (d.feather * side).toFloat()
                val turn = if (d.type == ShapeType.ELLIPSE) d.rotation.toFloat() else 0f
                rotate(turn, p) {
                    drawOval(look.fill, Offset(p.x - rx, p.y - ry), Size(2 * rx, 2 * ry), alpha = look.fillAlpha)
                    drawOval(look.line, Offset(p.x - rx, p.y - ry), Size(2 * rx, 2 * ry), style = Stroke(look.lineWidth.toPx()))
                    if (f > 0f) drawOval(look.line, Offset(p.x - rx - f, p.y - ry - f), Size(2 * (rx + f), 2 * (ry + f)),
                        alpha = 0.6f, style = Stroke(look.lineWidth.toPx()))
                }
                handle(p, look)
            }
            ShapeType.GRADIENT -> {
                val cpt = d.center ?: return@clipRect
                val p = at(cpt)
                val far = 2 * max(w, h)
                rotate(d.rotation.toFloat(), p) {
                    // the tinted side below the line, fading over the compression's width
                    val band = max(1f, (d.compression * side).toFloat())
                    drawRect(look.fill, Offset(p.x - far, p.y + band), Size(2 * far, far), alpha = look.fillAlpha)
                    drawRect(look.fill, Offset(p.x - far, p.y), Size(2 * far, band), alpha = look.fillAlpha / 2)
                    segment(p.x - far, p.y, p.x + far, p.y, look.lineWidth.toPx() * 3, look.shadow, 0.6f)
                    segment(p.x - far, p.y, p.x + far, p.y, look.lineWidth.toPx(), look.line)
                }
                handle(p, look)
            }
            ShapeType.PATH -> {
                val pts = (d.points + listOfNotNull(d.pending)).map { at(it) }
                line(pts, d.closed, look)
                for (p in pts) handle(p, look)
            }
        }
    }
}
