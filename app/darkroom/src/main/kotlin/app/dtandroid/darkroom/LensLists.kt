// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/*
 * Brief P6d: lens correction's camera and lens by hand (iop/lens.cc, Lensfun method). What desktop computes in
 * camera_menu_fill, _lens_menu_fill and _lens_set, as pure functions: the maker groups of the pickers, the search
 * filter, the focal length, aperture and distance lists, the number text and typed values. The engine gives the
 * Lensfun database and the state (engine-api.md 5.4, module actions of `lens`).
 */

const val LENS_OP = "lens"

/** Schema nodes of the lens module's panel (ui-schema.json, darktable 5.7: the pinned commit): rows built by hand. */
object LensNodes {
    const val CAMERA_ROW = 7
    const val LENS_ROW = 10
    const val VALUES_ROW = 13
    fun handles(id: Int) = id == CAMERA_ROW || id == LENS_ROW || id == VALUES_ROW
}

/** A Lensfun camera: desktop's menu shows `model` or `model (variant)` under the maker. */
data class LensCamera(val maker: String, val model: String, val variant: String?, val mount: String, val crop: Double) {
    val label: String get() = if (variant == null) model else "$model ($variant)"
    /** _camera_set(): "maker, model" */
    val title: String get() = if (maker.isBlank()) model else "$maker, $model"
}

/** A Lensfun lens; the detail fields are only in get_state's lens. */
data class LensInfo(
    val maker: String,
    val model: String,
    val minFocal: Double,
    val maxFocal: Double,
    val minAperture: Double?,
    val maxAperture: Double?,
    val cropFactor: Double? = null,
    val type: String? = null,
    val mounts: List<String> = emptyList(),
) {
    val title: String get() = if (maker.isBlank()) model else "$maker, $model"
    /** _lens_set(): "%g-%gmm", or "%gmm" for a prime */
    val focalText: String get() = if (minFocal < maxFocal) "${LensValues.g(minFocal)}-${LensValues.g(maxFocal)}mm" else "${LensValues.g(minFocal)}mm"
    /** _lens_set(): "%g-%g", or "%g" (Lensfun's f-numbers: the widest opening first) */
    val apertureText: String? get() {
        val lo = minAperture ?: return null
        val hi = maxAperture ?: 0.0
        return if (lo < hi) "${LensValues.g(lo)}-${LensValues.g(hi)}" else LensValues.g(lo)
    }
    /** what the lens button's tooltip shows on desktop, one fact per line */
    val details: List<String> get() = listOfNotNull(
        "focal range: $focalText",
        apertureText?.let { "aperture: f/$it" },
        mounts.takeIf { it.isNotEmpty() }?.let { "mount: ${it.joinToString(", ")}" },
        type?.let { "type: $it" },
        cropFactor?.let { "crop factor: ${LensValues.g(it)}" },
    )
}

/** What the module's panel shows (get_state): camera and lens as Lensfun knows them, else Exif's names. */
data class LensState(
    val hasBeenSet: Boolean,
    val cameraParam: String,
    val lensParam: String,
    val exifMaker: String,
    val exifModel: String,
    val exifLens: String,
    val camera: LensCamera?,
    val lens: LensInfo?,
    val focal: Double,
    val aperture: Double,
    val distance: Double,
    val crop: Double,
) {
    /** _camera_set(): the found camera, else what the file says, else the stored name */
    val cameraTitle: String get() = camera?.title ?: when {
        exifModel.isNotBlank() -> if (exifMaker.isNotBlank()) "$exifMaker, $exifModel" else exifModel
        else -> cameraParam
    }
    val cameraHint: String? get() = if (camera != null) null
    else if (cameraTitle.isNotBlank()) "This camera is not in the Lensfun database. Tap to pick one."
    else "No camera information found. Tap to pick one."
    val cameraDetails: List<String> get() = camera?.let {
        listOfNotNull("mount: ${it.mount}", "crop factor: ${LensValues.g(it.crop)}")
    } ?: emptyList()
    /** _lens_set(NULL): the stored name, else Exif's */
    val lensTitle: String get() = lens?.title ?: lensParam.ifBlank { exifLens }
    val lensHint: String? get() = if (lens != null) null else "No matching lens in the Lensfun database. Tap to pick one from the full list."
    /** the camera whose name goes with set_lens (the stored one when Lensfun knows it) */
    val cameraForLensList: String? get() = camera?.model
}

/** One row of a value list: the text desktop shows and the value it writes (sscanf of that text). */
data class LensValueEntry(val text: String, val value: Double, val current: Boolean = false)

object LensValues {
    private const val N = Double.POSITIVE_INFINITY
    /** _lens_set(): focal_values, with the infinities that bound it */
    private val FOCAL = doubleArrayOf(
        -N, 4.5, 8.0, 10.0, 12.0, 14.0, 15.0, 16.0, 17.0, 18.0, 20.0, 24.0, 28.0, 30.0,
        31.0, 35.0, 38.0, 40.0, 43.0, 45.0, 50.0, 55.0, 60.0, 70.0, 75.0, 77.0, 80.0, 85.0,
        90.0, 100.0, 105.0, 110.0, 120.0, 135.0, 150.0, 200.0, 210.0, 240.0, 250.0, 300.0, 400.0, 500.0,
        600.0, 700.0, 800.0, 840.0, 1000.0, 1120.0, 1200.0, 1600.0, 2000.0, N,
    )
    /** _lens_set(): aperture_values */
    private val APERTURE = doubleArrayOf(
        -N, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.4, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.4,
        4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0,
        22.0, 25.0, 29.0, 32.0, 38.0, 45.0, 50.0, 54.0, 64.0, 90.0, N,
    )
    private const val SQRT2_F = 1.41421356f

    /** _precision(x, adj) of iop/lens.cc: decimals by magnitude */
    fun precision(x0: Double, adj: Double = 10.0): Int {
        val x = x0 * adj
        return when {
            x == 0.0 -> 1
            x < 1.0 -> if (x < 0.1) (if (x < 0.01) 5 else 4) else 3
            x < 100.0 -> if (x < 10.0) 2 else 1
            else -> 0
        }
    }

    /** the text of a value in the lists and on the rows: "%.*f" with _precision(x, 10) */
    fun text(x: Double): String = String.format(Locale.ROOT, "%.${precision(x)}f", x)

    /** C's "%g" (6 significant digits, trailing zeros dropped), for the lens label */
    fun g(x: Double): String {
        if (x == 0.0) return "0"
        val s = BigDecimal(x).round(MathContext(6)).stripTrailingZeros()
        return if (s.scale() < 0) s.setScale(0).toPlainString() else s.toPlainString()
    }

    /**
     * _lens_set(): the focal lengths from the table clipped to the lens's range. A lens minimum that is not in the
     * table takes the place of the table value below it; the maximum is the table's last value not above it (desktop's
     * insertion of the maximum never applies, so it is not made here either).
     */
    fun focalTable(minFocal: Double, maxFocal: Double): List<Double> {
        val v = FOCAL.copyOf()
        val n = v.size
        var ffi = 1
        var fli = -1
        for (i in 1 until n - 1) {
            if (v[i] < minFocal) ffi = i + 1
            if (v[i] > maxFocal && fli == -1) fli = i
        }
        if (v[ffi] > minFocal) { v[ffi - 1] = minFocal; ffi-- }
        if (maxFocal == 0.0 || fli < 0) fli = n - 2
        if (v[fli + 1] < maxFocal) { v[fli + 1] = maxFocal; ffi++ }
        if (fli < ffi) fli = ffi + 1
        return (0 until fli - ffi).map { v[ffi + it] }
    }

    /** _lens_set(): the f-numbers from the lens's widest opening (MinAperture; 0 when Lensfun does not know it) to f/90 */
    fun apertureTable(minAperture: Double): List<Double> {
        val v = APERTURE.copyOf()
        val n = v.size
        var ffi = 1
        val fli = n - 1
        for (i in 1 until n - 1) if (v[i] < minAperture) ffi = i + 1
        if (v[ffi] > minAperture) { v[ffi - 1] = minAperture; ffi-- }
        return (0 until fli - ffi).map { v[ffi + it] }
    }

    /** _lens_set(): 0.25 m, times the square root of 2 each step (in single precision), up to 1000 m */
    fun distanceTable(): List<Double> {
        val out = ArrayList<Double>()
        var v = 0.25f
        repeat(25) {
            if (v > 1000.0f) v = 1000.0f
            out += v.toDouble()
            if (v >= 1000.0f) return out
            v *= SQRT2_F
        }
        return out
    }

    /**
     * A list as desktop shows it: the current value first, then the table. A table entry that reads like one already
     * listed is left out (desktop lists a lens's own limit twice when it is not exactly a table value). A row writes
     * the number its text reads as, as the callbacks' sscanf do. Values of 0 or less are not listed (a lens with no
     * f-number in the database makes desktop list 0.0).
     */
    fun entries(current: Double, table: List<Double>): List<LensValueEntry> {
        val out = ArrayList<LensValueEntry>()
        val seen = HashSet<String>()
        val first = text(current)
        out += LensValueEntry(first, current, current = true)
        seen += first
        for (t in table) {
            if (!(t > 0.0) || t.isInfinite()) continue
            val s = text(t)
            if (seen.add(s)) out += LensValueEntry(s, s.toDouble())
        }
        return out
    }

    fun focalEntries(lens: LensInfo?, current: Double) =
        entries(current, if (lens != null) focalTable(lens.minFocal, lens.maxFocal) else focalTable(0.0, 0.0))

    fun apertureEntries(lens: LensInfo?, current: Double) = entries(current, apertureTable(lens?.minAperture ?: 0.0))

    fun distanceEntries(current: Double) = entries(current, distanceTable())

    private val NUMBER = Regex("""^(\d+\.?\d*|\.\d+)$""")

    /** A typed value: a plain positive number (a comma works as the decimal point); null when it is not one. */
    fun parseTyped(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (!NUMBER.matches(t)) return null
        val v = t.toDoubleOrNull() ?: return null
        return v.takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000.0 }
    }
}

/** A list of the picker: maker groups, as desktop's submenus, and the search filter. */
data class MakerGroup<T>(val maker: String, val items: List<T>)

object LensPicker {
    /** Makers in alphabetical order (g_utf8_collate: case does not decide), each with its entries in list order. */
    fun <T> group(items: List<T>, maker: (T) -> String): List<MakerGroup<T>> {
        val by = LinkedHashMap<String, MutableList<T>>()
        for (i in items) by.getOrPut(maker(i)) { ArrayList() } += i
        return by.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key }).map { MakerGroup(it.key, it.value) }
    }

    /** Every word of [query] must be in the text (maker, model and variant), case apart. A blank query keeps everything. */
    fun <T> filter(items: List<T>, query: String, text: (T) -> String): List<T> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        return items.filter { i -> val t = text(i).lowercase(); words.all { t.contains(it) } }
    }

    /** Entries of a maker in alphabetical order (a phone's list is long; desktop's are in the database's order). */
    private fun <T> MakerGroup<T>.sorted(label: (T) -> String) = MakerGroup(maker, items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, label)))

    fun cameras(all: List<LensCamera>, query: String): List<MakerGroup<LensCamera>> =
        group(filter(all, query) { "${it.maker} ${it.model} ${it.variant ?: ""} ${it.label}" }) { it.maker }.map { g -> g.sorted { it.label } }

    /** The database lists some lens names twice (other mounts): one row each, as the engine picks a lens by maker and model. */
    fun lenses(all: List<LensInfo>, query: String): List<MakerGroup<LensInfo>> =
        group(filter(all.distinctBy { it.maker to it.model }, query) { "${it.maker} ${it.model}" }) { it.maker }.map { g -> g.sorted { it.model } }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    fun parseCamera(o: JsonObject): LensCamera? {
        val model = o["model"].str() ?: return null
        return LensCamera(o["maker"].str() ?: "", model, o["variant"].str(), o["mount"].str() ?: "", o["crop_factor"].num() ?: 1.0)
    }

    fun parseLens(o: JsonObject): LensInfo? {
        val model = o["model"].str() ?: return null
        return LensInfo(
            o["maker"].str() ?: "", model, o["min_focal"].num() ?: 0.0, o["max_focal"].num() ?: 0.0,
            o["min_aperture"].num(), o["max_aperture"].num(), o["crop_factor"].num(), o["type"].str(),
            (o["mounts"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList(),
        )
    }

    fun parseCameras(json: JsonElement?): List<LensCamera> = (json as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseCamera) } ?: emptyList()

    fun parseLenses(json: JsonElement?): List<LensInfo> = (json as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseLens) } ?: emptyList()

    /** get_state */
    fun parseState(json: JsonElement?): LensState? {
        val o = json as? JsonObject ?: return null
        val exif = o["exif"] as? JsonObject
        return LensState(
            hasBeenSet = (o["has_been_set"] as? JsonPrimitive)?.booleanOrNull ?: false,
            cameraParam = o["camera_param"].str() ?: "",
            lensParam = o["lens_param"].str() ?: "",
            exifMaker = exif?.get("maker").str() ?: "",
            exifModel = exif?.get("model").str() ?: "",
            exifLens = exif?.get("lens").str() ?: "",
            camera = (o["camera"] as? JsonObject)?.let(::parseCamera),
            lens = (o["lens"] as? JsonObject)?.let(::parseLens),
            focal = o["focal"].num() ?: 0.0,
            aperture = o["aperture"].num() ?: 0.0,
            distance = o["distance"].num() ?: 0.0,
            crop = o["crop"].num() ?: 1.0,
        )
    }
}
