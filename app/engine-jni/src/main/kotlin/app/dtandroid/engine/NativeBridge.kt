// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import java.nio.ByteBuffer

/**
 * One-to-one JNI view of engine/include/dtbridge.h (brief G0).
 *
 * Every function except [renderCancel], [logSince], [logLine],
 * [startLogCapture] and [dlopenCheck] must be called on the engine thread
 * (see [Engine]). Functions returning Int return 0 or a negative DTB_E_*
 * code; [lastError] then holds the message.
 *
 * The library behind this object (libdtjni.so) is loaded by [EngineInstaller]
 * after the engine's own libraries; nothing here loads it.
 */
object NativeBridge {
    const val OK = 0
    const val E_STATE = -1
    const val E_ARG = -2
    const val E_NOT_FOUND = -3
    const val E_TYPE = -4
    const val E_RANGE = -5
    const val E_IO = -6
    const val E_READ_ONLY = -7
    const val E_CANCELLED = -8
    const val E_ENGINE = -9

    fun codeName(code: Int): String = when (code) {
        OK -> "ok"
        E_STATE -> "DTB_E_STATE"
        E_ARG -> "DTB_E_ARG"
        E_NOT_FOUND -> "DTB_E_NOT_FOUND"
        E_TYPE -> "DTB_E_TYPE"
        E_RANGE -> "DTB_E_RANGE"
        E_IO -> "DTB_E_IO"
        E_READ_ONLY -> "DTB_E_READ_ONLY"
        E_CANCELLED -> "DTB_E_CANCELLED"
        E_ENGINE -> "DTB_E_ENGINE"
        else -> "code $code"
    }

    // helpers that are not part of dtbridge.h
    @JvmStatic external fun startLogCapture(): Boolean
    @JvmStatic external fun logSince(since: Long, next: LongArray): String?
    @JvmStatic external fun logLine(line: String)
    @JvmStatic external fun dlopenCheck(path: String): String?

    // lifecycle
    @JvmStatic external fun init(
        configdir: String, cachedir: String, datadir: String, moduledir: String,
        tmpdir: String, library: String, threads: Int,
    ): Int
    @JvmStatic external fun shutdown()
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun engineVersion(): String
    @JvmStatic external fun pluginsJson(): String?
    @JvmStatic external fun setThreads(n: Int): Int
    @JvmStatic external fun getThreads(): Int
    @JvmStatic external fun cacheDrop(): Int

    // session
    @JvmStatic external fun xmpCheck(path: String): String?
    @JvmStatic external fun open(raw: String, xmp: String?): Int
    @JvmStatic external fun close(): Int
    @JvmStatic external fun isReadOnly(): Boolean
    @JvmStatic external fun modulesJson(): String?
    @JvmStatic external fun historyJson(): String?
    @JvmStatic external fun paramSetFloat(op: String, prio: Int, param: String, value: Float): Int
    @JvmStatic external fun setEnabled(op: String, prio: Int, enabled: Boolean): Int
    @JvmStatic external fun historySeal()

    // rendering
    @JvmStatic external fun renderSize(scale: Float, wh: IntArray): Int
    @JvmStatic external fun render(x: Int, y: Int, w: Int, h: Int, scale: Float, out: ByteBuffer): Int
    @JvmStatic external fun renderPreview(maxSize: Int, wh: IntArray, status: IntArray): ByteArray?
    @JvmStatic external fun renderCancel()

    // output
    @JvmStatic external fun xmpWrite(path: String): Int
    @JvmStatic external fun exportJpeg(path: String, quality: Int, maxW: Int, maxH: Int): Int

    // Engine API v1, the whole core (brief P2b), for JniEngine. JSON getters put the
    // bridge's code into status[0] and return null on failure.
    const val JSON_ENGINE_INFO = 0
    const val JSON_IMAGE_INFO = 1
    const val JSON_MODULES = 2
    const val JSON_HISTORY = 3
    const val JSON_UNDO_STATE = 4
    @JvmStatic external fun jsonGet(what: Int, status: IntArray): String?
    /** modules() with times[0] = bridge call, times[1] = copy into a Java string, in ns */
    @JvmStatic external fun modulesTimed(times: LongArray, status: IntArray): String?
    @JvmStatic external fun setThreadsCode(n: Int): Int
    @JvmStatic external fun setThreadCpus(cpus: IntArray): Int
    @JvmStatic external fun xmpCheckStatus(path: String, status: IntArray): String?
    @JvmStatic external fun metadataReload(path: String): Int
    @JvmStatic external fun paramSet(op: String, prio: Int, path: String, valueJson: String): Int
    @JvmStatic external fun paramsSet(op: String, prio: Int, valuesJson: String): Int
    @JvmStatic external fun moduleReset(op: String, prio: Int): Int
    @JvmStatic external fun moduleAction(op: String, prio: Int, action: String, argsJson: String?, status: IntArray): String?
    /** kind 0 = new, 1 = duplicate; out[0] receives the new priority */
    @JvmStatic external fun instanceAdd(kind: Int, op: String, prio: Int, out: IntArray): Int
    @JvmStatic external fun instanceDelete(op: String, prio: Int): Int
    @JvmStatic external fun instanceMove(op: String, prio: Int, direction: Int): Int
    @JvmStatic external fun instanceRename(op: String, prio: Int, name: String): Int
    const val HISTORY_GOTO = 0
    const val HISTORY_COMPRESS = 1
    const val HISTORY_SEAL = 2
    const val HISTORY_UNDO = 3
    const val HISTORY_REDO = 4
    @JvmStatic external fun historyOp(what: Int, arg: Int): Int
    /** region into [out] (exactly w*h*4 bytes); copied[0] tells whether JNI had to copy the array */
    @JvmStatic external fun renderRegionInto(x: Int, y: Int, w: Int, h: Int, scale: Float, out: ByteArray, copied: BooleanArray): Int
    @JvmStatic external fun exportWith(path: String, optionsJson: String?): Int
}
