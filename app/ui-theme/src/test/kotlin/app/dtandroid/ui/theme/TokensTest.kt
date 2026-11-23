// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokensTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("dtandroid/theme/$name")!!.readBytes().decodeToString()

    @Test
    fun initialValuesAreTheSpecificationsTable() {
        val t = DtTokens.load()
        assertEquals(Color(0xFF262626), t.colors.bg)
        assertEquals(Color(0xFF303030), t.colors.panel)
        assertEquals(Color(0xFF3B3B3B), t.colors.header)
        assertEquals(Color(0xFF1B1B1B), t.colors.border)
        assertEquals(Color(0xFF777777), t.colors.surround)
        assertEquals(Color(0xFF5E5E5E), t.colors.filmstrip)
        assertEquals(Color(0xFF5E5E5E), t.colors.selected)
        assertEquals(Color(0xFFB9B9B9), t.colors.text)
        assertEquals(Color(0xFFD4D4D4), t.colors.textStrong)
        assertEquals(Color(0xFFF1F1F1), t.colors.textBright)
        assertEquals(Color(0xFF9E9E9E), t.colors.textMuted)
        assertEquals(Color(0xFF848484), t.colors.textDisabled)
        assertEquals(Color(0xFF242424), t.colors.sliderTrack)
        assertEquals(Color(0xFF8A8A8A), t.colors.sliderFill)
        assertEquals(Color(0xFFFFE000), t.colors.maskOverlay)
        assertEquals(44.dp, t.sizes.touch)
        assertEquals(44.dp, t.sizes.moduleHeader)
        assertEquals(48.dp, t.sizes.sliderRow)
        assertEquals(6.dp, t.sizes.sliderTrack)
        assertEquals(4.dp, t.sizes.radiusControl)
        assertEquals(listOf(14f, 13f, 12f, 11f), listOf(t.text.body, t.text.label, t.text.small, t.text.tiny).map { it.value })
    }

    @Test
    fun touchTargetsAreAtLeast44() {
        val t = DtTokens.load()
        for (s in listOf(t.sizes.touch, t.sizes.moduleHeader, t.sizes.sliderRow, t.sizes.groupTabs, t.sizes.historyRow,
            t.sizes.sheetItem, t.sizes.menuItem, t.sizes.thumbPhoneWidth, t.sizes.thumbPhoneHeight)) assertTrue("$s", s >= 44.dp)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }

    /** ui-spec.md section 5: text keeps 4.5:1 on its background. Disabled items are exempt, as in WCAG. */
    private fun checkContrast(t: DtTokens) {
        val c = t.colors
        val pairs = listOf(
            "text on bg" to (c.text to c.bg), "text on panel" to (c.text to c.panel), "text on header" to (c.text to c.header),
            "textStrong on header" to (c.textStrong to c.header), "textStrong on panel" to (c.textStrong to c.panel),
            "textBright on selected" to (c.textBright to c.selected), "textBright on header" to (c.textBright to c.header),
            "textMuted on bg" to (c.textMuted to c.bg), "textMuted on panel" to (c.textMuted to c.panel),
            "textDim on header" to (c.textDim to c.header), "textDim on bg" to (c.textDim to c.bg),
            "textStrong on banner" to (c.textStrong to c.banner), "text on filmstrip? no: only thumbnails" to (c.textBright to c.bg),
        )
        val bad = pairs.filter { contrast(it.second.first, it.second.second) < 4.5 }
            .map { "${it.first}: %.2f".format(contrast(it.second.first, it.second.second)) }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun defaultPaletteHasReadableContrast() = checkContrast(DtTokens.load())

    @Test
    fun alternativePaletteHasReadableContrastAndOverrides() {
        val t = DtTokens.load(res("alt-tokens.json"))
        assertEquals(Color(0xFF1E2329), t.colors.bg)
        assertEquals(44.dp, t.sizes.touch) // not overridden
        assertEquals(emptyList<String>(), t.unknownOverrides)
        checkContrast(t)
    }

    @Test
    fun badOverridesAreRefused() {
        for (bad in listOf("""{"color.bg": 12}""", """{"color.bg": "#12"}""", """{"size.touch": "#ffffff"}""", "not json")) {
            try { DtTokens.load(bad); fail(bad) } catch (e: TokenException) { /* expected */ }
        }
        assertEquals(listOf("color.nope"), DtTokens.load("""{"color.nope": "#ffffff"}""").unknownOverrides)
    }

    private fun keys(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json)
        .let { it as kotlinx.serialization.json.JsonObject }.keys - "comment"

    // brief PUI2a: the modern skin
    @Test
    fun modernOverrideParsesAndEveryKeyIsAToken() {
        val modern = res("modern-tokens.json")
        assertEquals(emptyList<String>(), (keys(modern) - keys(res("tokens.json"))).toList())
        val t = DtTokens.load(Skin.MODERN)
        assertEquals(emptyList<String>(), t.unknownOverrides)
        assertEquals(Color(0xFF161616), t.colors.bg)
        assertEquals(Color(0xFF1E1E1E), t.colors.panel)
        assertEquals(Color(0xFF262626), t.colors.raised)
        assertEquals(Color(0xFF333333), t.colors.border)
        assertEquals(Color(0xFFD08A3C), t.colors.accent)
        assertEquals(Color(0xFFD08A3C), t.colors.sliderFill)
        assertEquals(8.dp, t.sizes.radiusControl)
        assertEquals(8.dp, t.sizes.radiusSheet)
        assertEquals(4.dp, t.sizes.sliderTrack)
        assertEquals(48.dp, t.sizes.moduleHeader)
        assertEquals(52.dp, t.sizes.sliderRow)
        assertEquals(1.dp, t.sizes.moduleSeparator)
        assertEquals(15f, t.text.moduleName.value)
        assertEquals(13f, t.text.label.value)
        checkContrast(t)
        // the same file as an override on the default tokens gives the same skin
        assertEquals(t.colors.bg, DtTokens.load(modern).colors.bg)
    }

    @Test
    fun darktableSkinKeepsTodaysLooks() {
        val t = DtTokens.load()
        assertEquals(Color(0xFFF1F1F1), t.colors.accentPower) // power icon on: textBright
        assertEquals(t.colors.textBright, t.colors.accentPower)
        assertEquals(t.colors.text, t.colors.accentTab) // selected tab indicator: text
        assertEquals(t.colors.header, t.colors.raised)
        assertEquals(t.text.body, t.text.moduleName)
        assertEquals(2.dp, t.sizes.moduleGap)
        assertEquals(0.dp, t.sizes.moduleSeparator)
        assertEquals(false, t.shapes.sliderThumbRound)
        assertEquals(false, t.shapes.tabularValues)
        assertEquals(Skin.DARKTABLE, Skin.fromKey(null))
        assertEquals(Skin.DARKTABLE, Skin.fromKey("nonsense"))
        assertEquals(Skin.MODERN, Skin.fromKey("modern"))
    }

    @Test
    fun sliderThumbValues() {
        assertEquals(false, DtTokens.load("""{"shape.sliderThumb": "marker"}""").shapes.sliderThumbRound)
        assertEquals(true, DtTokens.load("""{"shape.sliderThumb": "round"}""").shapes.sliderThumbRound)
        assertEquals(true, DtTokens.load(Skin.MODERN).shapes.sliderThumbRound)
        for (bad in listOf("""{"shape.sliderThumb": "square"}""", """{"shape.sliderThumb": 3}""")) {
            try { DtTokens.load(bad); fail(bad) } catch (e: TokenException) { /* expected */ }
        }
    }

    // brief PUI3a: the l1 skin, l1-tokens.json on top of modern-tokens.json
    @Test
    fun l1SkinStacksOnModernAndEveryKeyIsAToken() {
        assertEquals(emptyList<String>(), (keys(res("l1-tokens.json")) - keys(res("tokens.json"))).toList())
        val t = DtTokens.load(Skin.L1)
        assertEquals(emptyList<String>(), t.unknownOverrides)
        assertEquals(true, t.l1.layout)
        // the modern skin's values stay: accent, corners, slider thumb
        val m = DtTokens.load(Skin.MODERN)
        assertEquals(m.colors.accent, t.colors.accent)
        assertEquals(m.colors.panel, t.colors.panel)
        assertEquals(m.sizes.radiusControl, t.sizes.radiusControl)
        assertEquals(true, t.shapes.sliderThumbRound)
        assertEquals(0.95f, t.l1.panelAlpha)
        assertEquals(250, t.l1.fadeOutMs)
        assertEquals(100, t.l1.fadeInMs)
        assertTrue(t.l1.toolsRow >= 44.dp && t.l1.sectionsRow >= 44.dp && t.l1.sideColumn >= 44.dp)
        assertTrue(t.l1.sectionCell >= 44.dp)
        checkContrast(t)
        // the other skins keep the panels layout
        assertEquals(false, m.l1.layout)
        assertEquals(false, DtTokens.load().l1.layout)
    }

    @Test
    fun skinKeysRoundTrip() {
        for (s in Skin.entries) assertEquals(s, Skin.fromKey(s.key))
        assertEquals(Skin.L1, Skin.fromKey("l1"))
        assertEquals(listOf("darktable", "modern", "l1"), Skin.entries.map { it.key })
        try { DtTokens.load("""{"shape.darkroom": "tabs"}"""); fail() } catch (e: TokenException) { /* expected */ }
    }
}
