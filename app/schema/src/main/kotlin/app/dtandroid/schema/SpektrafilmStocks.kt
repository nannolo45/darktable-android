// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Brief P2j: spektrafilm's film stock, print paper and spectral upsampling lists (`src/iop/spektrafilm.c`), on
 * parameters and the data pack the app carries (P2f). The pack's profiles are read once by
 * `tools/spektrafilm-stock-table.py` into `schema/overrides/spektrafilm.json` (checked against the pack in CI), in
 * the order `_scan_profiles` sorts them; this class repeats `gui_update` (which entry is shown), `_film_changed`,
 * `_paper_changed`, `_upsampling_changed` and the helpers they call (`_current_film_entry`, `_auto_paper_entry`,
 * `_effective_paper_entry`, `_development_default`, `_stamp_lut_hash`).
 */
class SpektrafilmStocks(
    val stocks: List<Stock>,
    val tables: List<Table>,
    /** the pack's default table (`sf_pack_lut_hash`), stamped into `lut_hash` by `_stamp_lut_hash` */
    val packLutHash: Long,
    val filmNode: Int,
    val paperNode: Int,
    val upsamplingNode: Int,
) {
    data class Stock(
        val stock: String,
        val name: String,
        val printing: Boolean,
        val positive: Boolean,
        val bw: Boolean,
        /** development times; empty or one entry = characterised at one development */
        val devTimes: List<Double>,
        val targetPrint: String?,
    ) {
        val hash: Long = nameHash(stock)
    }

    data class Table(val label: String, val hash: Long, val reflectance: Boolean)

    companion object {
        /** `_name_hash`: darktable's `dt_hash` (djb2 with xor, 64 bit) folded to 32 bits; 0 is reserved. */
        fun nameHash(s: String): Long {
            var h = 5381L
            for (b in s.encodeToByteArray()) h = ((h shl 5) + h) xor (b.toLong() and 0xff)
            val h32 = (h xor (h ushr 32)) and 0xffffffffL
            return if (h32 == 0L) 1L else h32
        }

        private val FILM_GROUPS = listOf(Triple(false, false, "negative color"), Triple(false, true, "negative monochrome"),
            Triple(true, false, "positive color"), Triple(true, true, "positive monochrome"))
        private val PAPER_GROUPS = listOf(false to "color", true to "monochrome")
        const val SCAN = -2
        const val AUTO = -1
    }

    /** `_development_default`: the middle time of a family (floor-middle when even), 0 for a single one. */
    fun developmentDefault(e: Stock?): Double = if (e != null && e.devTimes.size > 1) e.devTimes[(e.devTimes.size - 1) / 2] else 0.0

    private fun hash(p: JsonObject, name: String): Long = p.num(name)?.toLong() ?: 0L
    private fun bool(p: JsonObject, name: String): Boolean = (p.num(name) ?: 0.0) != 0.0

    fun byHash(h: Long, printing: Boolean): Stock? = stocks.firstOrNull { it.hash == h && it.printing == printing }

    /** `_current_film_entry`: the edit's film, or kodak_gold_200 / the first film for 0 or a stock not in the pack. */
    fun currentFilm(p: JsonObject): Stock? {
        val h = hash(p, "film_hash")
        if (h != 0L) byHash(h, false)?.let { return it }
        var fallback: Stock? = null
        for (e in stocks) {
            if (e.printing) continue
            if (fallback == null || e.stock == "kodak_gold_200") fallback = e
        }
        return fallback
    }

    /** `_auto_paper_entry`: the film's target print, else a paper of the same channel model, else the first paper. */
    fun autoPaper(film: Stock?): Stock? {
        var first: Stock? = null
        var same: Stock? = null
        val target = film?.targetPrint?.takeIf { it.isNotEmpty() }
        for (pe in stocks) {
            if (!pe.printing) continue
            if (first == null) first = pe
            if (film != null && same == null && pe.bw == film.bw) same = pe
            if (target != null && pe.stock == target) return pe
        }
        return same ?: first
    }

    /** `_effective_paper_entry`: the paper printed on, null while scanning. */
    fun effectivePaper(p: JsonObject): Stock? {
        if (bool(p, "scan_film")) return null
        val ph = hash(p, "paper_hash")
        val pinned = if (ph != 0L) byHash(ph, true) else null
        return pinned ?: autoPaper(currentFilm(p))
    }

    /** `_stamp_lut_hash`, for an edit that renders on the carried pack: record its table when none is recorded. */
    private fun stamp(p: JsonObject, out: MutableMap<String, JsonPrimitive>) {
        val cur = out["lut_hash"]?.content?.toLongOrNull() ?: hash(p, "lut_hash")
        if (cur == 0L && packLutHash != 0L) out["lut_hash"] = JsonPrimitive(packLutHash)
    }

    // ------------------------------------------------------------------ film stock

    inner class Film : ComputedList {
        override val controlId get() = filmNode
        /** combobox entries: (label, stock or null for a section) */
        private val rows: List<Pair<String, Stock?>> = buildList {
            for ((positive, bw, label) in FILM_GROUPS) {
                val members = stocks.filter { !it.printing && it.positive == positive && it.bw == bw }
                if (members.isEmpty()) continue
                add(label to null)
                for (e in members) add(e.name to e)
            }
        }

        override fun entries(p: JsonObject) = rows.mapIndexed { i, (l, s) -> ComputedEntry(l, i, s != null, s == null) }
        override fun current(p: JsonObject): Int? = currentFilm(p)?.let { f -> rows.indexOfFirst { it.second == f }.takeIf { it >= 0 } }

        override fun choose(index: Int, p: JsonObject): Map<String, JsonPrimitive> {
            val e = rows.getOrNull(index)?.second ?: return emptyMap()
            val prev = currentFilm(p)
            val out = linkedMapOf<String, JsonPrimitive>()
            out["film_hash"] = JsonPrimitive(e.hash)
            out["development_min"] = f32(developmentDefault(e).toFloat())
            var scan = bool(p, "scan_film")
            val rebaseline = e.positive || prev == null || prev.positive
            if (rebaseline && scan != e.positive) {
                scan = e.positive
                out["scan_film"] = JsonPrimitive(scan)
                out["scan_black_correction"] = JsonPrimitive(e.positive)
                out["scan_white_correction"] = JsonPrimitive(e.positive)
            }
            val paperHash = hash(p, "paper_hash")
            if (paperHash == 0L) {
                // _rebaseline_print_development on the paper in force after the change
                val paper = if (scan) null else autoPaper(e)
                out["print_development_min"] = f32((paper?.let { developmentDefault(it) } ?: 0.0).toFloat())
            }
            stamp(p, out)
            return out
        }
    }

    // ------------------------------------------------------------------ print paper

    inner class Paper : ComputedList {
        override val controlId get() = paperNode
        /** (label, data): data SCAN, AUTO, a stock, or null for a section */
        private val rows: List<Pair<String, Any?>> = buildList {
            add("none (scan the film)" to SCAN)
            add("auto (follow film stock)" to AUTO)
            for ((bw, label) in PAPER_GROUPS) {
                val members = stocks.filter { it.printing && it.bw == bw }
                if (members.isEmpty()) continue
                add(label to null)
                for (e in members) add(e.name to e)
            }
        }

        /** `_update_paper_auto_entry`: the auto entry names the paper it resolves to. */
        private fun autoLabel(p: JsonObject) = autoPaper(currentFilm(p))?.let { "auto (${it.name})" } ?: "auto"

        override fun entries(p: JsonObject) = rows.mapIndexed { i, (l, d) ->
            ComputedEntry(if (d == AUTO) autoLabel(p) else l, i, d != null, d == null)
        }

        override fun current(p: JsonObject): Int? {
            if (bool(p, "scan_film")) return rows.indexOfFirst { it.second == SCAN }
            val ph = hash(p, "paper_hash")
            if (ph == 0L) return rows.indexOfFirst { it.second == AUTO }
            val pe = byHash(ph, true) ?: autoPaper(currentFilm(p)) ?: stocks.firstOrNull { it.printing }
            return rows.indexOfFirst { it.second == pe }.takeIf { it >= 0 }
        }

        /** `_update_print_sensitivity`: a positive film has no print stage. */
        override fun enabled(p: JsonObject) = currentFilm(p)?.positive != true

        override fun choose(index: Int, p: JsonObject): Map<String, JsonPrimitive> {
            val d = rows.getOrNull(index)?.second ?: return emptyMap()
            val out = linkedMapOf<String, JsonPrimitive>()
            val scan = d == SCAN
            if (bool(p, "scan_film") != scan) out["scan_film"] = JsonPrimitive(scan)
            if (d == SCAN || d == AUTO) {
                out["paper_hash"] = JsonPrimitive(0L)
                val paper = if (scan) null else autoPaper(currentFilm(p))
                out["print_development_min"] = f32((paper?.let { developmentDefault(it) } ?: 0.0).toFloat())
            } else {
                val pe = d as Stock
                out["paper_hash"] = JsonPrimitive(pe.hash)
                out["print_development_min"] = f32(developmentDefault(pe).toFloat())
            }
            stamp(p, out)
            return out
        }
    }

    // ------------------------------------------------------------------ spectral upsampling

    inner class Upsampling : ComputedList {
        override val controlId get() = upsamplingNode
        override fun entries(p: JsonObject) = tables.mapIndexed { i, t -> ComputedEntry(t.label, i, true) }

        override fun current(p: JsonObject): Int? {
            if (tables.isEmpty()) return null
            val uh = hash(p, "upsampling_hash")
            var pos = if (uh != 0L) tables.indexOfFirst { it.hash == uh } else -1
            val lh = hash(p, "lut_hash")
            if (pos < 0 && lh != 0L) pos = tables.indexOfFirst { it.hash == lh }
            return if (pos < 0) 0 else pos
        }

        /** desktop greys the list out while there is nothing to choose (`gui_update`, ntab > 1) */
        override fun enabled(p: JsonObject) = tables.size > 1

        override fun choose(index: Int, p: JsonObject): Map<String, JsonPrimitive> {
            val t = tables.getOrNull(index) ?: return emptyMap()
            return linkedMapOf("upsampling_hash" to JsonPrimitive(t.hash), "pack_hash" to JsonPrimitive(0L), "lut_hash" to JsonPrimitive(0L))
        }
    }
}
