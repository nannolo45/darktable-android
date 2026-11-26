// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.lpl.LutFolder
import app.dtandroid.lpl.LutLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Brief P6b (PLAN.md 4.6; the manual's "lut 3D" page): desktop's file button opens a chooser inside the LUT folder
 * of its preferences and stores the path relative to that folder. Here the folder is the app's own (`luts/`), filled
 * by "Add from phone…" and from LightPhotoLibrary; the chooser lists its tree (folders, the files darktable reads;
 * others greyed with the reason). A file the photo names but the phone lacks says so, with "Get from
 * LightPhotoLibrary" when LPL has it.
 */

const val LUT3D_OP = "lut3d"

/** What the app gives the darkroom: the folder, Android's picker, and LightPhotoLibrary's copy. */
interface LutHost {
    val folder: LutFolder
    /** Android's picker (one or several files); the host copies them into [subfolder] with [Lut3dFiles.import]. */
    fun pickFromPhone(subfolder: String)
    /** Whether a server LPL has [path] (not removed there). */
    fun onServer(path: String): Boolean
    /** "Get from LightPhotoLibrary": an error text, or null when the file is on the phone now. */
    suspend fun fetch(path: String): String?
}

/** The chooser's state for one lut 3D instance. */
data class Lut3dUi(
    /** the instance whose chooser is open, null = closed */
    val open: InstanceKey? = null,
    val level: LutLevel = LutLevel("", emptyList(), emptyList()),
    val message: String? = null,
    val busy: Boolean = false,
    /** picked files whose name is taken: replace them? (name, subfolder) */
    val askReplace: List<Pair<String, String>> = emptyList(),
    /** counts the folder's changes, so screens read [Lut3dFiles.exists] again */
    val tick: Int = 0,
)

class Lut3dFiles(private val c: DarkroomController) {
    private val _ui = MutableStateFlow(Lut3dUi())
    val ui: StateFlow<Lut3dUi> = _ui.asStateFlow()

    @Volatile var host: LutHost? = null
        private set
    private var watch: Job? = null
    private val pendingBytes = HashMap<Pair<String, String>, ByteArray>()

    /** Called once by the app: the engine is told the folder, and again whenever its files change. */
    fun attach(h: LutHost) {
        host = h
        watch?.cancel()
        watch = c.launch {
            h.folder.version.collect {
                // an engine not started yet is told at the next change; the folder is created first
                h.folder.root.mkdirs()
                try { lutFolderChanged(h.folder.root.path) } catch (e: app.dtandroid.engine.api.EngineException) { }
                _ui.update { u -> u.copy(tick = u.tick + 1, level = if (u.open != null) h.folder.level(u.level.dir) else u.level) }
            }
        }
    }

    /** Whether the photo's [path] is in the folder. */
    fun exists(path: String): Boolean = host?.folder?.has(path) == true

    fun onServer(path: String): Boolean = host?.onServer(path) == true

    fun open(key: InstanceKey, current: String) {
        val f = host?.folder ?: run { _ui.update { it.copy(message = "no LUT folder") }; return }
        // the folder of the current file, as desktop's chooser starts there
        val dir = current.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && f.fileOf("$it/x.cube") != null } ?: ""
        _ui.update { it.copy(open = key, level = f.level(dir), message = null) }
    }

    fun close() = _ui.update { it.copy(open = null, message = null) }

    fun enter(dir: String) { val f = host?.folder ?: return; _ui.update { it.copy(level = f.level(dir)) } }

    fun up() = enter(_ui.value.level.dir.substringBeforeLast('/', ""))

    fun choose(path: String) {
        val key = _ui.value.open ?: return
        c.launch {
            chooseLutFile(key, path)
            this@Lut3dFiles.close()
        }
    }

    fun addFromPhone() { host?.pickFromPhone(_ui.value.level.dir) }

    /** Files the user picked, copied into [subfolder]; taken names are asked about first. */
    fun import(files: List<Pair<String, ByteArray>>, subfolder: String) {
        val f = host?.folder ?: return
        val added = ArrayList<String>()
        val refused = ArrayList<String>()
        val ask = ArrayList<Pair<String, String>>()
        for ((name, bytes) in files) {
            when (val r = f.import(name, subfolder, { bytes.inputStream() })) {
                is app.dtandroid.lpl.LutImport.Added -> added += r.path
                is app.dtandroid.lpl.LutImport.Exists -> { ask += name to subfolder; synchronized(pendingBytes) { pendingBytes[name to subfolder] = bytes } }
                is app.dtandroid.lpl.LutImport.Refused -> refused += "${r.name}: ${r.reason}"
            }
        }
        _ui.update {
            it.copy(level = if (it.open != null) f.level(it.level.dir) else it.level, askReplace = it.askReplace + ask,
                message = listOfNotNull(added.takeIf { a -> a.isNotEmpty() }?.let { a -> "added ${a.size}: ${a.joinToString()}" },
                    refused.takeIf { r -> r.isNotEmpty() }?.let { r -> "not added: ${r.joinToString("; ")}" }).joinToString(" · ").ifEmpty { null })
        }
    }

    /** The answer for the first picked file whose name was taken. */
    fun answerReplace(replace: Boolean) {
        val f = host?.folder ?: return
        val first = _ui.value.askReplace.firstOrNull() ?: return
        val bytes = synchronized(pendingBytes) { pendingBytes.remove(first) }
        var msg: String? = null
        if (replace && bytes != null) {
            msg = when (val r = f.import(first.first, first.second, { bytes.inputStream() }, replace = true)) {
                is app.dtandroid.lpl.LutImport.Added -> "replaced ${r.path}"
                is app.dtandroid.lpl.LutImport.Refused -> "not added: ${r.name}: ${r.reason}"
                else -> null
            }
        }
        _ui.update { it.copy(askReplace = it.askReplace.drop(1), message = msg ?: it.message,
            level = if (it.open != null) f.level(it.level.dir) else it.level) }
    }

    /** A line under the file row (the host's picker could not read a file). */
    fun note(text: String) = _ui.update { it.copy(message = text) }

    /** "Get from LightPhotoLibrary" for the photo's missing file. */
    fun fetch(path: String) {
        val h = host ?: return
        _ui.update { it.copy(busy = true, message = null) }
        c.launch {
            val err = h.fetch(path)
            _ui.update { it.copy(busy = false, message = err) }
        }
    }
}
