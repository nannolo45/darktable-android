// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenCheckPageTest {
    private val saved = Origin("http", "192.168.1.20", 8080)

    @Test
    fun openAcceptsOnlyTheSavedOrigin() {
        for (u in listOf(
            "http://192.168.1.20:8080/",
            "http://192.168.1.20:8080/?photo=12",
            "http://192.168.1.20:8080/?series=4&x=%20y",
            "HTTP://192.168.1.20:8080/?photo=12",
        )) assertEquals(u, OpenRules.accept(saved, u))
        for (u in listOf(
            null, "",
            "https://192.168.1.20:8080/?photo=12",
            "http://192.168.1.20:8081/?photo=12",
            "http://example.org/?photo=12",
            "http://192.168.1.20:8080@example.org/",
            "http://user:pw@192.168.1.20:8080/",
            "http://192.168.1.20:8080\\@example.org/",
            "javascript:alert(1)//http://192.168.1.20:8080/",
            "file:///data/data/app.dtandroid.lplshell/shared_prefs/shell.xml",
            "content://app.dtandroid.lplshell/x",
            "intent://edit?ids=1#Intent;scheme=lpldt;package=app.dtandroid;end",
            "http://192.168.1.20:8080/ x",
            "http://192.168.1.20:8080/\n",
            "http://192.168.1.20:8080/<script>",
            "http://192.168.1.20:8080/" + "a".repeat(9000),
        )) assertNull(u, OpenRules.accept(saved, u))
        assertNull(OpenRules.accept(null, "http://192.168.1.20:8080/"))
    }

    private val lplManifest = """{"name":"LightPhotoLibrary","short_name":"LPL","start_url":"/"}"""

    private fun server(routes: Map<String, HttpAnswer>): (String) -> HttpAnswer = { url -> routes[url] ?: HttpAnswer(404, "not found") }

    @Test
    fun serverCheck() {
        assertEquals(CheckResult.Lpl, ServerCheck.run(saved, server(mapOf("http://192.168.1.20:8080/manifest.webmanifest" to HttpAnswer(200, lplManifest)))))
        // manifest linked from the page under another name
        assertEquals(CheckResult.Lpl, ServerCheck.run(saved, server(mapOf(
            "http://192.168.1.20:8080/" to HttpAnswer(200, """<html><head><link href="/static/app.webmanifest" rel="manifest"></head></html>"""),
            "http://192.168.1.20:8080/static/app.webmanifest" to HttpAnswer(200, """{"short_name":"Light Photo Library"}"""),
        ))))
        // answers, but something else
        assertEquals(CheckResult.NotLpl, ServerCheck.run(saved, server(mapOf("http://192.168.1.20:8080/" to HttpAnswer(200, "<html>router</html>")))))
        assertEquals(CheckResult.NotLpl, ServerCheck.run(saved, server(mapOf(
            "http://192.168.1.20:8080/manifest.webmanifest" to HttpAnswer(200, """{"name":"Photo Viewer"}"""),
        ))))
        assertEquals(CheckResult.NotLpl, ServerCheck.run(saved, server(mapOf(
            "http://192.168.1.20:8080/manifest.webmanifest" to HttpAnswer(401, lplManifest),
        ))))
        // a manifest on another origin is not followed
        var asked = ArrayList<String>()
        val r = ServerCheck.run(saved) { url ->
            asked += url
            if (url.endsWith(":8080/")) HttpAnswer(200, """<link rel=manifest href="http://example.org/m.json">""") else HttpAnswer(404, "")
        }
        assertEquals(CheckResult.NotLpl, r)
        assertEquals(listOf("http://192.168.1.20:8080/manifest.webmanifest", "http://192.168.1.20:8080/"), asked)
        // nothing answers
        assertTrue(ServerCheck.run(saved) { throw IOException("connect timed out") } is CheckResult.Unreachable)
    }

    @Test
    fun manifestParsing() {
        assertTrue(ServerCheck.manifestNamesLpl(lplManifest))
        assertTrue(!ServerCheck.manifestNamesLpl("not json"))
        assertTrue(!ServerCheck.manifestNamesLpl("""["LightPhotoLibrary"]"""))
        assertTrue(!ServerCheck.manifestNamesLpl("""{"name":7,"description":"LightPhotoLibrary"}"""))
        assertEquals("m.json", ServerCheck.manifestHref("<LINK REL='manifest' HREF='m.json'>"))
        assertEquals("/a.webmanifest", ServerCheck.manifestHref("<link rel=icon href=x.png><link rel=manifest href=/a.webmanifest>"))
        assertNull(ServerCheck.manifestHref("<link rel=stylesheet href=a.css>"))
        assertEquals("http://192.168.1.20:8080/m.json", ServerCheck.resolveSameOrigin(saved, "./m.json"))
        assertEquals("http://192.168.1.20:8080/x/m.json", ServerCheck.resolveSameOrigin(saved, "/x/m.json"))
        assertEquals("http://192.168.1.20:8080/m.json", ServerCheck.resolveSameOrigin(saved, "http://192.168.1.20:8080/m.json"))
        assertNull(ServerCheck.resolveSameOrigin(saved, "//example.org/m.json"))
        assertNull(ServerCheck.resolveSameOrigin(saved, "https://example.org/m.json"))
        assertNull(ServerCheck.resolveSameOrigin(saved, "data:application/json,{}"))
    }

    @Test
    fun fileChooserTypes() {
        val ext = mapOf("jpg" to "image/jpeg", "png" to "image/png")
        assertEquals(emptyList<String>(), PageRules.chooserMimeTypes(emptyList()) { ext[it] })
        assertEquals(emptyList<String>(), PageRules.chooserMimeTypes(listOf("")) { ext[it] })
        assertEquals(listOf("image/*"), PageRules.chooserMimeTypes(listOf("image/*")) { ext[it] })
        assertEquals(listOf("image/jpeg", "image/png"), PageRules.chooserMimeTypes(listOf(".jpg,.png", "image/jpeg")) { ext[it] })
        // an extension the phone does not know (a RAW format): any file, so it can still be picked
        assertEquals(emptyList<String>(), PageRules.chooserMimeTypes(listOf("image/*", ".raf")) { ext[it] })
        assertEquals(emptyList<String>(), PageRules.chooserMimeTypes(listOf("*/*")) { ext[it] })
    }

    @Test
    fun downloads() {
        assertEquals(PageRules.DownloadDecision.Manager, PageRules.download(saved, "http://192.168.1.20:8080/api/photos/3/original"))
        assertEquals(PageRules.DownloadDecision.Outside, PageRules.download(saved, "https://example.org/file.zip"))
        assertEquals(PageRules.DownloadDecision.Unsupported, PageRules.download(saved, "blob:http://192.168.1.20:8080/0f1e"))
        assertEquals(PageRules.DownloadDecision.Unsupported, PageRules.download(saved, "data:text/plain,x"))
        assertEquals("photo.jpg", PageRules.safeFileName("photo.jpg"))
        assertEquals("passwd", PageRules.safeFileName("../../etc/passwd"))
        assertEquals("bashrc", PageRules.safeFileName(".bashrc"))
        assertEquals("download", PageRules.safeFileName(""))
        assertEquals("ab.txt", PageRules.safeFileName("a\u0000b.txt"))
    }
}
