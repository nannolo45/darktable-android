// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.speed.GpuCheck
import app.dtandroid.darkroom.speed.GpuDetails
import app.dtandroid.darkroom.speed.OpenClLibraries
import app.dtandroid.darkroom.speed.TestRaw
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.OpenClDiagnostics
import app.dtandroid.engine.api.OpenClInfo
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brief P4d deliverable 3: the app's library loading order with a fake loader, the diagnostics text, the reason line. */
class P4dGpuDetailsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeFiles(val dirs: Map<String, List<String>>, val texts: Map<String, String>) : OpenClLibraries.Files {
        override fun list(dir: String) = dirs[dir]
        override fun read(path: String) = texts[path]
    }

    private val files = FakeFiles(
        mapOf("/vendor/etc" to listOf("public.libraries.txt", "other.txt"), "/system/etc" to listOf("public.libraries-foo.txt")),
        mapOf("/vendor/etc/public.libraries.txt" to "libOpenCL.so\nlibfoo.so\n",
            "/proc/self/maps" to "7f00-7f10 r-xp 00000000 fd:00 1 /vendor/lib64/libOpenCL.so\n" +
                "7f10-7f20 r--p 00000000 fd:00 1 /vendor/lib64/libOpenCL.so\n7f20-7f30 r-xp 0 fd:00 2 /system/lib64/libc.so\n"))

    @Test
    fun candidatesInOrderAndOnlyLoadedOnesPreferred() {
        val asked = ArrayList<String>()
        val p = OpenClLibraries({ n -> asked += n; if (n == "OpenCL") throw UnsatisfiedLinkError("library \"libOpenCL.so\" not found") }, files).probe()
        assertEquals(listOf("OpenCL", "OpenCL_adreno"), asked)
        assertEquals(listOf("OpenCL", "OpenCL_adreno"), p.tries.map { it.name })
        assertTrue(p.tries[0].error!!.contains("UnsatisfiedLinkError") && p.tries[0].error!!.contains("not found"))
        assertNull(p.tries[1].error)
        assertEquals(listOf("libOpenCL_adreno.so"), p.preferred)
        // the file that cannot be read is named, other lists and lines are left out
        assertEquals(listOf("/system/etc/public.libraries-foo.txt: not readable", "/vendor/etc/public.libraries.txt: libOpenCL.so"), p.publicLibraries)
        assertEquals(listOf("/vendor/lib64/libOpenCL.so"), p.mapped)

        val both = OpenClLibraries({}, files).probe()
        assertEquals(listOf("libOpenCL.so", "libOpenCL_adreno.so"), both.preferred)
        val none = OpenClLibraries({ throw UnsatisfiedLinkError("no") }, FakeFiles(emptyMap(), emptyMap())).probe()
        assertEquals(emptyList<String>(), none.preferred)
        assertEquals(emptyList<String>(), none.publicLibraries)
    }

    private val log = """
        [dt_dlopencl_init] found specified opencl runtime library 'libOpenCL.so'
        [opencl_init] opencl library 'libOpenCL.so' found on your system and loaded, preference 'libOpenCL.so'
        [opencl_init] found 1 platform
        [dt_opencl_device_init]
           DEVICE:                   0: 'QUALCOMM Adreno(TM) 650'
           PLATFORM, VENDOR & ID:    QUALCOMM Snapdragon(TM), QUALCOMM, ID=1
           *** insufficient global memory 700 MB) ***
        [opencl_init] no suitable devices found.
        [opencl_init] FINALLY: opencl PREFERENCE=ON is NOT AVAILABLE and NOT ENABLED
    """.trimIndent()

    /** the bridge's JSON, as dtb_opencl_diagnostics writes it */
    private fun diag(platforms: Int = 1, logText: String? = log) = OpenClDiagnostics.fromJson(Json.parseToJsonElement("""
        {"built": true, "started": true, "preferred": "libOpenCL.so", "chosen": "libOpenCL.so", "loaded": "libOpenCL.so",
         "library_tries": [{"name": "libOpenCL.so", "from": "app", "loaded": true, "symbol": true, "error": null, "status": ${if (platforms > 0) 0 else -1001}, "platforms": $platforms}],
         "init_log": ${if (logText == null) "null" else Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(logText))},
         "init_log_cut": false,
         "platforms": [{"name": "QUALCOMM Snapdragon(TM)", "vendor": "QUALCOMM", "version": "OpenCL 2.0 QUALCOMM build", "profile": "FULL_PROFILE", "devices_status": 0,
           "devices": [{"name": "QUALCOMM Adreno(TM) 650", "vendor": "QUALCOMM", "version": "OpenCL 2.0 Adreno(TM) 650", "driver": "OpenCL 2.0 QUALCOMM build",
             "c_version": "OpenCL C 2.0 Adreno(TM) 650", "extensions": "cl_khr_fp16 cl_khr_3d_image_writes", "type": "GPU", "available": true,
             "image_support": true, "little_endian": true, "global_memory": 734003200, "max_alloc": 183500800}]}]}
    """.trimIndent()))

    private val noDevice = OpenClInfo(built = true, allowed = true, started = true, library = "libOpenCL.so")

    @Test
    fun reasonNamesWhatDarktableDecided() {
        assertEquals("darktable did not use the device: insufficient global memory 700 MB)", GpuDetails.reason(diag(), noDevice))
        assertEquals("libOpenCL.so opened but reports no OpenCL platform (clGetPlatformIDs status -1001)", GpuDetails.reason(diag(platforms = 0), noDevice))
        val unopened = OpenClDiagnostics(built = true, started = true,
            tries = listOf(OpenClDiagnostics.Try("libOpenCL.so", "darktable", false, false, "dlopen failed: library \"libOpenCL.so\" not found", null, null)))
        assertTrue(GpuDetails.reason(unopened, noDevice)!!.startsWith("no OpenCL library could be opened (libOpenCL.so: dlopen failed"))
        assertEquals("this engine was built without OpenCL", GpuDetails.reason(OpenClDiagnostics.NONE, null))
        val log2 = "[check platform] platform 'QUALCOMM Snapdragon(TM)' with key 'clplatform_qualcommsnapdragontm' is NOT active"
        assertTrue(GpuDetails.reason(diag(logText = log2), noDevice)!!.contains("is NOT active"))
    }

    @Test
    fun textHasEverySection() {
        val p = OpenClLibraries({}, files).probe()
        val t = GpuDetails.text(listOf("app: test"), "GPU off: x", p, diag(), noDevice)
        for (s in listOf("app: test", "result: GPU off: x", "reason: darktable did not use the device", "== the app's System.loadLibrary",
            "  OpenCL: loaded", "asked the engine to try first: libOpenCL.so, libOpenCL_adreno.so", "/vendor/etc/public.libraries.txt: libOpenCL.so",
            "1. libOpenCL.so (app): opened, clGetPlatformIDs status 0, platforms 1", "platform QUALCOMM Snapdragon(TM)",
            "device QUALCOMM Adreno(TM) 650 | GPU", "image support true", "memory 700 MB", "extensions: cl_khr_fp16",
            "== darktable's decisions", "  *** insufficient global memory 700 MB) ***", "FINALLY", "== darktable's OpenCL log"))
            assertTrue("missing \"$s\" in\n$t", t.contains(s))
        // a long log keeps its start and end
        val long = "x".repeat(GpuDetails.LOG_HEAD) + "\nMIDDLE\n" + "y".repeat(GpuDetails.LOG_TAIL) + "\n[opencl_init] FINALLY: end"
        val t2 = GpuDetails.text(emptyList(), null, null, diag(logText = long), noDevice)
        assertTrue(t2.contains("characters left out") && !t2.contains("MIDDLE") && t2.contains("FINALLY: end"))
    }

    @Test
    fun compileErrorsComeWithTheCompilerMessages() {
        val l = "[dt_opencl_device_init] testing program `basic.cl'\n[opencl_build_program] could not build program: CL_BUILD_PROGRAM_FAILURE\n" +
            "basic.cl:12: error: use of undeclared identifier\n[dt_opencl_device_init] failed to compile program `basic.cl'\n"
        val d = GpuDetails.decisions(l)
        assertEquals("[opencl_build_program] could not build program: CL_BUILD_PROGRAM_FAILURE", d[0])
        assertEquals("    basic.cl:12: error: use of undeclared identifier", d[1])
        assertTrue(GpuDetails.reason(diag(logText = l), noDevice)!!.contains("could not build program"))
    }

    /** GpuCheck asks the engine to try the app's libraries before it starts OpenCL, and says why no device was used. */
    private class NoDeviceGpu(base: FakeEngine, val d: OpenClDiagnostics) : DarktableEngine by base {
        val calls = ArrayList<String>()
        var started = false
        override suspend fun openclInfo() = OpenClInfo(built = true, allowed = true, started = started, library = if (started) "libOpenCL.so" else null)
        override suspend fun openclLibraries(names: List<String>) { calls += "libraries $names" }
        override suspend fun openclSet(enabled: Boolean): OpenClInfo { calls += "opencl $enabled"; if (enabled) started = true; return openclInfo() }
        override suspend fun openclDiagnostics() = d
    }

    @Test
    fun gpuCheckTriesTheAppsLibrariesFirst() = runBlocking<Unit> {
        val e = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        e.init(EnginePaths(tmp.root.path, tmp.root.path, tmp.root.path, tmp.root.path, tmp.root.path))
        val raw = File(tmp.root, TestRaw.NAME).apply { writeBytes(ByteArray(64)) }
        val g = NoDeviceGpu(e, diag())
        val check = GpuCheck(g, raw.path, OpenClLibraries({ if (it == "OpenCL") throw UnsatisfiedLinkError("no") }, files))
        val r = check.run()
        assertTrue(r is GpuCheck.Result.Off)
        assertEquals("darktable did not use the device: insufficient global memory 700 MB)", r.text)
        assertEquals("libraries [libOpenCL_adreno.so]", g.calls[0])
        assertEquals("opencl true", g.calls[1])
        assertEquals(listOf("libOpenCL_adreno.so"), check.probe!!.preferred)
    }
}
