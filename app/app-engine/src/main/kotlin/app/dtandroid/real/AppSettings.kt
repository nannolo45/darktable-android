// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import app.dtandroid.ui.theme.Skin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Brief PUI2a: the app's own settings, today one: `interface`, the skin (`darktable`, the default, or
 * `modern`). Kept in `<file>` as JSON, written whole; [file] null keeps it in memory (tests). An unknown
 * or unreadable value means `darktable`.
 */
class AppSettings(private val file: File?) {
    private val _skin = MutableStateFlow(load())

    /** The skin now; collectors see a change at once. */
    val skin: StateFlow<Skin> = _skin.asStateFlow()

    private fun load(): Skin {
        val f = file?.takeIf { it.isFile } ?: return Skin.DARKTABLE
        val o = runCatching { Json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull() ?: return Skin.DARKTABLE
        return Skin.fromKey((o["interface"] as? JsonPrimitive)?.contentOrNull)
    }

    /** Switch the skin. Throws [java.io.IOException] when it cannot be stored. */
    @Synchronized
    fun setSkin(skin: Skin) {
        file?.let { f ->
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(buildJsonObject { put("interface", skin.key) }.toString())
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("cannot write ${f.name}") }
        }
        _skin.value = skin
    }
}
