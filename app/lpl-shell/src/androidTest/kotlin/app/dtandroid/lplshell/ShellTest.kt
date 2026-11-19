// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.toPackage
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dtandroid.lplshell.rules.OpenRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Brief P2c item 7, instrumented part: the shell against [StubServer] on the
 * emulator. Taps are real touch events on the web view (Espresso), so the page
 * sees user activation as it would with a finger.
 */
@RunWith(AndroidJUnit4::class)
class ShellTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var server: StubServer? = null
    private var scenario: ActivityScenario<ShellActivity>? = null

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
        Intents.init()
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            Intents.release()
            server?.close()
            for (u in mediaFiles) runCatching { ctx.contentResolver.delete(u, null, null) }
        }
    }

    /** Intents the shell started (the test's own launch of the shell left out). */
    private fun outgoing() = Intents.getIntents().filter { it.component?.className != ShellActivity::class.java.name }

    // ---- helpers

    private fun prefs() = ctx.getSharedPreferences(ShellActivity.PREFS, 0)
    private fun saved() = prefs().getString(ShellActivity.KEY_SERVER, null)
    private fun startServer(port: Int = 0, lpl: Boolean = true) = StubServer(port, lpl).also { server = it }
    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun launch(intent: Intent? = null): ActivityScenario<ShellActivity> =
        (if (intent == null) ActivityScenario.launch(ShellActivity::class.java) else ActivityScenario.launch<ShellActivity>(intent))
            .also { scenario = it }

    private fun <T> onMain(block: (ShellActivity) -> T): T {
        var r: Any? = null
        scenario!!.onActivity { r = block(it) }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        onMain { it.webView.evaluateJavascript(script) { v -> out = v ?: ""; latch.countDown() } }
        assertTrue("javascript did not answer: $script", latch.await(10, TimeUnit.SECONDS))
        return out
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        fail("timed out waiting for $what")
    }

    private fun href() = js("location.href").trim('"')

    private fun waitForPage(url: String) = waitFor("page $url (now ${runCatching { href() }.getOrNull()})") {
        href() == url && js("document.readyState") == "\"complete\""
    }

    private fun waitForText(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** A screenshot for people to look at (not compared): files/shots/<name>.png, pulled by the CI script. */
    private fun shot(name: String) {
        Thread.sleep(500)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = java.io.File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tapPage() {
        onView(isAssignableFrom(WebView::class.java)).perform(click())
    }

    /** Saved server, shell open on the start page. */
    private fun openOnStart(): StubServer {
        val s = startServer()
        prefs().edit().putString(ShellActivity.KEY_SERVER, s.origin).commit()
        launch()
        waitForPage(s.origin + "/")
        return s
    }

    /** A page whose one link covers the screen. */
    private fun showLink(s: StubServer, href: String): String {
        val url = s.origin + "/tap?to=" + enc(href)
        onMain { it.webView.loadUrl(url) }
        waitForPage(url)
        return url
    }

    private val FILE_SIZE = 1234

    private val r1Query = "server=http%3A%2F%2F127.0.0.1%3A8080&ids=12,13,14&start=13&series=4&title=Spring%20walk"

    // ---- first start

    @Test
    fun firstStartChecksTheAddressAndSavesIt() {
        val s = startServer()
        launch()
        compose.onNodeWithTag(ShellTags.SETUP).assertExists()
        shot("01-first-start")
        compose.onNodeWithTag(ShellTags.ADDRESS).performTextInput("127.0.0.1:${s.port}/library/")
        compose.onNodeWithText(ShellTexts.CONNECT).performClick()
        waitFor("address saved") { saved() == s.origin }
        waitForPage(s.origin + "/")
        shot("07-page")
        assertTrue(s.requests.toString(), s.requests.contains("GET /manifest.webmanifest"))
        assertEquals(Screen.Web, onMain { it.ui.screen })
        // brief P2c item 3: the user agent says the page runs in the shell
        assertTrue(s.lastUserAgent ?: "none", s.lastUserAgent!!.endsWith(" LPLShell/" + BuildConfig.VERSION_NAME))
    }

    @Test
    fun firstStartWarnsWhenTheServerIsNotLplAndLetsTheUserContinue() {
        val s = startServer(lpl = false)
        launch()
        compose.onNodeWithTag(ShellTags.ADDRESS).performTextInput(s.origin)
        compose.onNodeWithText(ShellTexts.CONNECT).performClick()
        waitForText(ShellTexts.NOT_LPL)
        shot("02-not-lpl-warning")
        assertNull(saved())
        compose.onNodeWithText(ShellTexts.USE_ANYWAY).performClick()
        waitFor("address saved") { saved() == s.origin }
        waitForPage(s.origin + "/")
    }

    @Test
    fun firstStartRefusesABadAddressWithoutAsking() {
        launch()
        compose.onNodeWithTag(ShellTags.ADDRESS).performTextInput("ftp://192.168.1.20")
        compose.onNodeWithText(ShellTexts.CONNECT).performClick()
        waitForText(ShellTexts.problem(app.dtandroid.lplshell.rules.AddressProblem.SCHEME))
        shot("03-bad-address")
        assertNull(saved())
    }

    @Test
    fun firstStartWithNoServerShowsTheNativeScreenThenChangeAddress() {
        val port = freePort()
        launch()
        compose.onNodeWithTag(ShellTags.ADDRESS).performTextInput("127.0.0.1:$port")
        compose.onNodeWithText(ShellTexts.CONNECT).performClick()
        waitForText(ShellTexts.UNREACHABLE_TITLE)
        compose.onNodeWithText("http://127.0.0.1:$port").assertExists()
        compose.onNodeWithText(ShellTexts.TRY_AGAIN).assertExists()
        shot("04-first-start-unreachable")
        assertNull(saved())
        // "change address" goes back to the field with the address in it
        compose.onNodeWithText(ShellTexts.CHANGE_ADDRESS).performClick()
        compose.onNodeWithTag(ShellTags.SETUP).assertExists()
        compose.onNodeWithText("http://127.0.0.1:$port").assertExists()
        // the server comes up; connecting now works
        val s = startServer(port)
        compose.onNodeWithText(ShellTexts.CONNECT).performClick()
        waitFor("address saved") { saved() == s.origin }
        waitForPage(s.origin + "/")
    }

    // ---- server not reachable later

    @Test
    fun unreachableSavedServerShowsTheNativeScreenAndTryAgainRecovers() {
        val port = freePort()
        prefs().edit().putString(ShellActivity.KEY_SERVER, "http://127.0.0.1:$port").commit()
        launch()
        waitForText(ShellTexts.UNREACHABLE_TITLE)
        compose.onNodeWithTag(ShellTags.UNREACHABLE).assertExists()
        compose.onNodeWithText(ShellTexts.CHANGE_ADDRESS).assertExists()
        shot("05-unreachable")
        assertTrue(onMain { it.webView.visibility } != android.view.View.VISIBLE)
        val s = startServer(port)
        compose.onNodeWithText(ShellTexts.TRY_AGAIN).performClick()
        waitFor("web view shown") { onMain { it.ui.screen } == Screen.Web }
        waitForPage(s.origin + "/")
        assertEquals(0, compose.onAllNodesWithTag(ShellTags.UNREACHABLE).fetchSemanticsNodes().size)
    }

    // ---- staying on the origin

    @Test
    fun sameOriginLinkStaysInside() {
        val s = openOnStart()
        showLink(s, s.origin + "/other")
        tapPage()
        waitForPage(s.origin + "/other")
        assertEquals(emptyList<Intent>(), outgoing())
    }

    @Test
    fun otherOriginOpensOutside() {
        val s = openOnStart()
        val foreign = "http://localhost:${s.port}/other"
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
        val tap = showLink(s, foreign)
        tapPage()
        waitFor("browser intent") { outgoing().any { it.dataString == foreign } }
        val i = outgoing().first { it.dataString == foreign }
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertTrue(i.categories.contains(Intent.CATEGORY_BROWSABLE))
        assertNull(i.`package`)
        Thread.sleep(1000)
        assertEquals(tap, href())
        assertEquals(0, s.count("/other"))
    }

    // ---- back

    @Test
    fun backGoesThroughHistoryStatesThenLeaves() {
        val s = openOnStart()
        onMain { it.webView.loadUrl(s.origin + "/history") }
        waitForPage(s.origin + "/history")
        // two states pushed on taps, as LPL does when a sheet or a photo opens
        tapPage()
        waitFor("state 1") { href() == s.origin + "/history?s=1" }
        tapPage()
        waitFor("state 2") { href() == s.origin + "/history?s=2" }
        val back = { onMain { it.onBackPressedDispatcher.onBackPressed() } }
        back()
        waitFor("back to state 1") { href() == s.origin + "/history?s=1" }
        back()
        waitFor("back to the page") { href() == s.origin + "/history" }
        back()
        waitForPage(s.origin + "/")
        // start of the history: the shell no longer takes the back press, so it leaves the app
        waitFor("back not taken") { !onMain { it.onBackPressedDispatcher.hasEnabledCallbacks() } }
        back()
        waitFor("left the app") { scenario!!.state != Lifecycle.State.RESUMED }
    }

    // ---- file chooser

    private val mediaFiles = ArrayList<Uri>()

    /** A small made-up file in the phone's Downloads (MediaStore), as a file picker would return it. */
    private fun mediaFile(name: String, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        mediaFiles += u
        ctx.contentResolver.openOutputStream(u)!!.use { it.write(ByteArray(FILE_SIZE) { k -> (k % 251).toByte() }) }
        return u
    }

    @Test
    fun fileChooserIsOfferedAndSeveralFilesReachThePage() {
        val s = openOnStart()
        onMain { it.webView.loadUrl(s.origin + "/upload") }
        waitForPage(s.origin + "/upload")
        val stamp = System.currentTimeMillis()
        val a = mediaFile("lpl-test-$stamp-a.jpg", "image/jpeg")
        val b = mediaFile("lpl-test-$stamp-b.raf", "application/octet-stream")
        val result = Intent().apply {
            clipData = ClipData.newRawUri("", a).also { it.addItem(ClipData.Item(b)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intending(hasAction(Intent.ACTION_GET_CONTENT)).respondWith(ActivityResult(Activity.RESULT_OK, result))
        tapPage()
        waitFor("file chooser") { outgoing().any { it.action == Intent.ACTION_GET_CONTENT } }
        val i = outgoing().first { it.action == Intent.ACTION_GET_CONTENT }
        assertTrue(i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(i.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", i.type)
        val want = "\"files:2:lpl-test-$stamp-a.jpg/$FILE_SIZE,lpl-test-$stamp-b.raf/$FILE_SIZE\""
        waitFor("files in the page (title ${runCatching { js("document.title") }.getOrNull()}, want $want)") {
            js("document.title") == want
        }
    }

    // ---- hand-off to the darktable app

    private fun assertHandOff(i: Intent) {
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("lpldt://edit?$r1Query", i.dataString)
        assertEquals("app.dtandroid", i.`package`)
        assertNull(i.component)
        assertNull(i.selector)
        assertTrue("extras: ${i.extras}", i.extras == null || i.extras!!.isEmpty)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), i.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, i.flags)
    }

    @Test
    fun handOffLinkStartsTheDarktableAppWithAnIntentBuiltByTheShell() {
        val s = openOnStart()
        intending(toPackage("app.dtandroid")).respondWith(ActivityResult(Activity.RESULT_OK, null))
        // R1 link with everything a hostile page could add: component, action, flags, extras, a selector
        val hostile = "intent://edit?$r1Query#Intent;scheme=lpldt;package=app.dtandroid;" +
            "component=app.dtandroid/.Evil;action=android.intent.action.DELETE;launchFlags=0x10008000;" +
            "S.extra=x;B.wipe=true;S.browser_fallback_url=http%3A%2F%2F127.0.0.1%2Fhelp;" +
            "SEL;scheme=http;package=com.example.evil;end"
        val tap = showLink(s, hostile)
        tapPage()
        waitFor("hand-off intent") { outgoing().any { it.`package` == "app.dtandroid" } }
        assertHandOff(outgoing().single { it.`package` == "app.dtandroid" })
        assertEquals(tap, href())

        // the direct form
        showLink(s, "lpldt://edit?$r1Query")
        tapPage()
        waitFor("second hand-off intent") { outgoing().count { it.`package` == "app.dtandroid" } == 2 }
        assertHandOff(outgoing().last { it.`package` == "app.dtandroid" })
    }

    @Test
    fun otherIntentLinksAreRefused() {
        val s = openOnStart()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
        for (link in listOf(
            "intent://edit?$r1Query#Intent;scheme=lpldt;package=com.example.evil;end",
            "intent://edit?$r1Query#Intent;scheme=https;package=app.dtandroid;end",
            "intent:#Intent;component=com.android.settings/.Settings;end",
        )) {
            val tap = showLink(s, link)
            tapPage()
            Thread.sleep(1500)
            assertEquals(link, tap, href())
        }
        assertEquals(emptyList<Intent>(), outgoing())
    }

    @Test
    fun handOffWithoutTheDarktableAppShowsAMessageAndStaysOnThePage() {
        val s = openOnStart()
        val tap = showLink(s, "lpldt://edit?$r1Query")
        tapPage()
        waitForText(ShellTexts.DARKTABLE_MISSING)
        compose.onNodeWithText(ShellTexts.GET_IT).assertExists()
        shot("06-darktable-missing")
        assertEquals(tap, href())
        assertEquals(Screen.Web, onMain { it.ui.screen })
    }

    // ---- OPEN from the darktable app

    @Test
    fun openActionLoadsOnlyPagesOfTheSavedServer() {
        val s = startServer()
        prefs().edit().putString(ShellActivity.KEY_SERVER, s.origin).commit()
        // the action resolves to the shell for http and https addresses (what the darktable app will send)
        for (scheme in listOf("http", "https")) {
            val q = Intent(OpenRules.ACTION_OPEN, Uri.parse("$scheme://192.168.1.20:8080/?photo=3")).setPackage(ctx.packageName)
            assertEquals(scheme, ShellActivity::class.java.name, ctx.packageManager.queryIntentActivities(q, 0).single().activityInfo.name)
        }
        // started with OPEN on the saved origin: that page
        launch(Intent(OpenRules.ACTION_OPEN, Uri.parse(s.origin + "/other")).setClass(ctx, ShellActivity::class.java))
        waitForPage(s.origin + "/other")
        // OPEN again while open (onNewIntent, as delivered to a singleTask activity): another origin is ignored
        onMain { it.onNewIntent(Intent(OpenRules.ACTION_OPEN, Uri.parse("http://localhost:${s.port}/other?x=1"))) }
        waitForPage(s.origin + "/")
        assertEquals(0, s.count("/other?x=1"))
        // and a page of the saved origin is opened
        onMain { it.onNewIntent(Intent(OpenRules.ACTION_OPEN, Uri.parse(s.origin + "/other?photo=3"))) }
        waitForPage(s.origin + "/other?photo=3")
        // started with OPEN on another origin: the start page
        scenario!!.close()
        launch(Intent(OpenRules.ACTION_OPEN, Uri.parse("http://example.org/?photo=3")).setClass(ctx, ShellActivity::class.java))
        waitForPage(s.origin + "/")
    }

    // ---- no reload

    @Test
    fun rotationAndBackgroundDoNotReloadThePage() {
        val s = openOnStart()
        js("window.marker = 42")
        val loads = s.count("/")
        val activity = onMain { System.identityHashCode(it) }
        onMain { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        Thread.sleep(2000)
        onMain { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        Thread.sleep(2000)
        assertEquals(activity, onMain { System.identityHashCode(it) })
        assertEquals("42", js("window.marker"))
        scenario!!.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1000)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        Thread.sleep(1000)
        assertEquals("42", js("window.marker"))
        assertEquals(loads, s.count("/"))
        assertFalse(onMain { it.isFinishing })
    }
}
