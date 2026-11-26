// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A few cameras and lenses for the fake engine's `lens` actions (brief P6d); the real engine reads Lensfun's database. */
internal object FakeLensfun {
    class Cam(val maker: String, val model: String, val variant: String?, val mount: String, val crop: Double) {
        fun json() = JsonObject(mapOf(
            "maker" to JsonPrimitive(maker), "model" to JsonPrimitive(model), "variant" to (variant?.let { JsonPrimitive(it) } ?: JsonNull),
            "mount" to JsonPrimitive(mount), "crop_factor" to JsonPrimitive(crop),
        ))
    }

    class Lens(val maker: String, val model: String, val minFocal: Double, val maxFocal: Double, val minAperture: Double?, val maxAperture: Double?, val mount: String, val type: String = "rectilinear") {
        fun json(detail: Boolean): JsonObject {
            val m = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "maker" to JsonPrimitive(maker), "model" to JsonPrimitive(model), "min_focal" to JsonPrimitive(minFocal), "max_focal" to JsonPrimitive(maxFocal),
                "min_aperture" to (minAperture?.let { JsonPrimitive(it) } ?: JsonNull), "max_aperture" to (maxAperture?.let { JsonPrimitive(it) } ?: JsonNull),
            )
            if (detail) {
                m["crop_factor"] = JsonPrimitive(1.6)
                m["type"] = JsonPrimitive(type)
                m["mounts"] = JsonArray(listOf(JsonPrimitive(mount)))
            }
            return JsonObject(m)
        }
    }

    /** the camera find_cameras suggests, and the word find_lenses searches for */
    const val exifCamera = "Canon EOS 40D"
    const val exifLensWord = "18-"

    val cameras = listOf(
        Cam("Canon", "Canon EOS 40D", null, "Canon EF-S", 1.6),
        Cam("Canon", "Canon EOS 5D Mark II", null, "Canon EF", 1.0),
        Cam("Nikon Corporation", "Nikon D90", null, "Nikon F AF", 1.5),
        Cam("Sony", "ILCE-7M3", "full frame", "Sony E", 1.0),
        Cam("Sony", "ILCE-7M3", "APS-C crop", "Sony E", 1.5),
        Cam("Fujifilm", "X-T3", null, "Fujifilm X", 1.5),
    )

    val lenses = listOf(
        Lens("Canon", "Canon EF 100mm f/2.8 Macro USM", 100.0, 100.0, 2.799999952316284, 32.0, "Canon EF"),
        Lens("Canon", "Canon EF-S 18-55mm f/3.5-5.6 IS", 18.0, 55.0, 3.5, 5.599999904632568, "Canon EF-S"),
        Lens("Sigma", "Sigma 4.5mm f/2.8 EX DC HSM circular fisheye", 4.5, 4.5, 2.799999952316284, 22.0, "Canon EF-S", "fisheye"),
        Lens("Tamron", "Tamron SP AF 17-50mm f/2.8 XR Di II", 17.0, 50.0, 2.799999952316284, 32.0, "Canon EF-S"),
    )
}
