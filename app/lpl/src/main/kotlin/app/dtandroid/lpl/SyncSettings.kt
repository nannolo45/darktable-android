// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Brief P2i deliverable 5: the app's LightPhotoLibrary settings, today one switch, "sync automatically"
 * (default on). Kept in `<dir>/settings.json`, written whole and atomically; [file] null keeps it in memory
 * only (tests, and agents built without a settings file).
 *
 * Off: nothing is sent or fetched by itself. "done" keeps the edit on the phone, the background job does not
 * sync, nothing is downloaded ahead. What the user asks for still works ("sync now", opening a photo that is
 * not on the phone), and so do jobs from LightPhotoLibrary addressed to this phone (the owner pressed them on
 * another device). The heartbeat goes on and reports the photos as they are (edited ones `dirty`).
 */
class SyncSettings(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val obj0 = loadObject()
    private val _autoSync = MutableStateFlow(obj0?.get("auto_sync").bool() ?: true)
    private val _lutDownload = MutableStateFlow(obj0?.get("lut_auto_download").bool() ?: false)

    /** "sync automatically": true unless the user switched it off. */
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    /** P6c: "Download LUT files from LightPhotoLibrary automatically": false unless the user switched it on. */
    val lutAutoDownload: StateFlow<Boolean> = _lutDownload.asStateFlow()

    private fun loadObject(): JsonObject? {
        val f = file ?: return null
        return readWhole(f)?.let { runCatching { json.parseToJsonElement(it.decodeToString()) as JsonObject }.getOrNull() }
    }

    private fun store(auto: Boolean, lut: Boolean) {
        file?.let { f ->
            f.parentFile?.mkdirs()
            atomicWrite(f, buildJsonObject { put("auto_sync", auto); put("lut_auto_download", lut) }.toString().toByteArray())
        }
    }

    /** Switch automatic syncing on or off. Throws [java.io.IOException] when the setting cannot be stored. */
    @Synchronized
    fun setAutoSync(on: Boolean) {
        store(on, _lutDownload.value)
        _autoSync.value = on
    }

    /** Switch the automatic download of LUT files on or off (P6c). Throws [java.io.IOException] when it cannot be stored. */
    @Synchronized
    fun setLutAutoDownload(on: Boolean) {
        store(_autoSync.value, on)
        _lutDownload.value = on
    }

    companion object {
        fun inMemory() = SyncSettings(null)

        /** What the list's header and the heartbeat's message say while the switch is off. */
        const val OFF_TEXT = "automatic sync is off"
    }
}
