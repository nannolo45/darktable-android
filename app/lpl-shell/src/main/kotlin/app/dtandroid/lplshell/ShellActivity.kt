// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import app.dtandroid.lplshell.rules.AddressResult
import app.dtandroid.lplshell.rules.CheckResult
import app.dtandroid.lplshell.rules.HandOff
import app.dtandroid.lplshell.rules.HttpAnswer
import app.dtandroid.lplshell.rules.LinkDecision
import app.dtandroid.lplshell.rules.LinkRules
import app.dtandroid.lplshell.rules.OpenRules
import app.dtandroid.lplshell.rules.Origin
import app.dtandroid.lplshell.rules.PageRules
import app.dtandroid.lplshell.rules.ServerAddress
import app.dtandroid.lplshell.rules.ServerCheck
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The LightPhotoLibrary shell (brief P2c): one web view on the saved server,
 * with two native screens (address, server not reachable) and a message line.
 * The web view is created once per activity and the activity handles
 * configuration changes itself, so rotation, split screen and returning from
 * the background never reload the page.
 */
class ShellActivity : ComponentActivity(), ShellActions {

    internal lateinit var webView: WebView
        private set
    internal val ui = ShellUi()
    internal var saved: Origin? = null
        private set

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var overlay: ComposeView
    private lateinit var bar: ComposeView
    private lateinit var userAgent: String

    /** set by onReceivedError for the main frame of the load in progress */
    private var pageFailed = false
    private var lastFailedUrl: String? = null
    private var clearHistoryWhenLoaded = false
    private var screenBeforeSetup: Screen = Screen.Web
    private var checkJob: Job? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBack()
    }

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        deliverFiles(r.resultCode, r.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tokens = DtTokens.load()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        saved = prefs.getString(KEY_SERVER, null)?.let { Origin.of(it) }
        userAgent = WebSettings.getDefaultUserAgent(this) + " LPLShell/" + BuildConfig.VERSION_NAME

        root = FrameLayout(this)
        webView = createWebView()
        overlay = ComposeView(this).apply { setContent { DtTheme(tokens) { ShellOverlay(ui, this@ShellActivity) } } }
        bar = ComposeView(this).apply { setContent { DtTheme(tokens) { MessageBar(ui, this@ShellActivity) } } }
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backCallback)
        ui.onChange = { refresh() }

        val s = saved
        val restored = savedInstanceState?.getBundle(KEY_WEB)?.let { webView.restoreState(it) } != null
        when {
            s == null -> showSetup("", cancelable = false)
            restored -> Unit
            else -> {
                webView.loadUrl(startUrl(intent, s))
                if (intent?.action == OpenRules.ACTION_CHANGE_ADDRESS) showSetup(s.url, cancelable = true)
            }
        }
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            OpenRules.ACTION_OPEN -> {
                val s = saved ?: return
                checkJob?.cancel()
                ui.screen = Screen.Web
                webView.loadUrl(startUrl(intent, s))
            }
            OpenRules.ACTION_CHANGE_ADDRESS -> if (ui.screen !is Screen.Setup) showSetup(saved?.url ?: "", cancelable = saved != null)
        }
    }

    /** OPEN's URL when it is on the saved origin, otherwise the start page (brief P2c item 5). */
    private fun startUrl(intent: Intent?, s: Origin): String =
        if (intent?.action == OpenRules.ACTION_OPEN) OpenRules.accept(s, intent.dataString) ?: s.startUrl else s.startUrl

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(KEY_WEB, Bundle().also { webView.saveState(it) })
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ---- web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // gestures belong to the page: no zoom controls of the web view's own
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // mixed-content rules stay at the platform default (never allow)
            userAgentString = userAgent
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, ua, disposition, mime, _ -> onDownload(url, ua, disposition, mime) }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return !navigate(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageFailed = false
            // safety net for main-frame loads shouldOverrideUrlLoading does not see (form posts): stay on the origin
            val s = saved ?: return
            val o = url?.let { Origin.of(it) } ?: return
            if (o != s) {
                Log.i(TAG, "left the origin without a link decision; stopped")
                view.stopLoading()
                openOutside(url)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (clearHistoryWhenLoaded && !pageFailed) {
                clearHistoryWhenLoaded = false
                view.clearHistory()
            }
            val sc = ui.screen
            if (!pageFailed && sc is Screen.Unreachable && sc.checking == null) ui.screen = Screen.Web
            updateBack()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBack()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.i(TAG, "main frame error ${error.errorCode}")
            pageFailed = true
            lastFailedUrl = request.url.toString()
            showUnreachable(untrusted = false)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (Origin.of(error.url) == saved) {
                pageFailed = true
                lastFailedUrl = error.url
                showUnreachable(untrusted = true)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // the page's process ended (memory): start a new web view instead of closing the app
            Log.w(TAG, "web view render process gone, crash=${detail.didCrash()}")
            root.removeView(webView)
            webView.destroy()
            webView = createWebView()
            root.addView(webView, 0, FrameLayout.LayoutParams(MATCH, MATCH))
            saved?.let { webView.loadUrl(it.startUrl) }
            refresh()
            return true
        }
    }

    /** true: load it in the web view; false: handled here (browser, darktable app) or refused. */
    internal fun navigate(url: String): Boolean {
        val s = saved ?: return false
        return when (val d = LinkRules.decide(s, url)) {
            LinkDecision.Stay -> true
            is LinkDecision.OpenOutside -> { openOutside(d.url); false }
            is LinkDecision.HandOffTo -> { handOff(d.handOff); false }
            is LinkDecision.Refuse -> { Log.i(TAG, "link refused: ${d.reason}"); false }
        }
    }

    private fun updateBack() {
        backCallback.isEnabled = when (val s = ui.screen) {
            is Screen.Setup -> s.cancelable
            is Screen.Unreachable -> s.checking != null || webView.canGoBack()
            Screen.Web -> webView.canGoBack()
        }
    }

    private fun onBack() {
        when (val s = ui.screen) {
            is Screen.Setup -> cancelSetup()
            is Screen.Unreachable -> if (s.checking != null) backToAddress() else if (webView.canGoBack()) {
                ui.screen = Screen.Web
                webView.goBack()
            }
            Screen.Web -> if (webView.canGoBack()) webView.goBack()
        }
        updateBack()
    }

    private fun refresh() {
        val web = ui.screen == Screen.Web
        overlay.visibility = if (web) View.GONE else View.VISIBLE
        webView.visibility = if (web) View.VISIBLE else View.INVISIBLE
        bar.visibility = if (ui.message != null) View.VISIBLE else View.GONE
        updateBack()
    }

    // ---- hand-off, browser, messages

    private fun handOff(h: HandOff) {
        try {
            startActivity(handOffIntent(h))
        } catch (e: ActivityNotFoundException) {
            showMessage(Message(ShellTexts.DARKTABLE_MISSING, ShellTexts.GET_IT) {
                ui.message = null
                openOutside(ShellTexts.RELEASES_URL)
            })
        }
    }

    private fun openOutside(url: String) {
        try {
            startActivity(browserIntent(url))
        } catch (e: ActivityNotFoundException) {
            showMessage(Message(ShellTexts.NO_BROWSER))
        }
    }

    private fun showMessage(m: Message, hideAfterMs: Long = 0) {
        ui.message = m
        if (hideAfterMs > 0) lifecycleScope.launch {
            delay(hideAfterMs)
            if (ui.message === m) ui.message = null
        }
    }

    override fun closeMessage() {
        ui.message = null
    }

    // ---- file chooser and downloads

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val types = PageRules.chooserMimeTypes(params.acceptTypes?.toList() ?: emptyList()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }
            val i = fileChooserIntent(types, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            try {
                chooser.launch(i)
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                callback.onReceiveValue(null)
                showMessage(Message(ShellTexts.NO_BROWSER))
            }
            return true
        }
    }

    private fun deliverFiles(resultCode: Int, data: Intent?) {
        val cb = fileCallback ?: return
        fileCallback = null
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it }
            else data.data?.let { uris += it }
        }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private fun onDownload(url: String, ua: String?, disposition: String?, mime: String?) {
        val s = saved ?: return
        when (PageRules.download(s, url)) {
            PageRules.DownloadDecision.Manager -> {
                val name = PageRules.safeFileName(URLUtil.guessFileName(url, disposition, mime))
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime?.ifEmpty { null })
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    .addRequestHeader("User-Agent", ua ?: userAgent)
                // the page's sign-in, when it is a cookie, goes with the download
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                try {
                    getSystemService(DownloadManager::class.java).enqueue(req)
                    showMessage(Message(ShellTexts.DOWNLOAD_STARTED + name), hideAfterMs = 4000)
                } catch (e: Exception) {
                    Log.w(TAG, "download not started: ${e.javaClass.simpleName}")
                    showMessage(Message(ShellTexts.DOWNLOAD_UNSUPPORTED))
                }
            }
            PageRules.DownloadDecision.Outside -> openOutside(url)
            PageRules.DownloadDecision.Unsupported -> showMessage(Message(ShellTexts.DOWNLOAD_UNSUPPORTED))
        }
    }

    // ---- native screens

    private fun showSetup(text: String, cancelable: Boolean) {
        if (ui.screen !is Screen.Setup) screenBeforeSetup = ui.screen
        checkJob?.cancel()
        ui.setupText = text
        ui.setupStatus = SetupStatus.Idle
        ui.screen = Screen.Setup(cancelable)
    }

    private fun showUnreachable(untrusted: Boolean) {
        val address = saved?.url ?: return
        val sc = ui.screen
        if (sc is Screen.Setup) return
        ui.screen = Screen.Unreachable(address, untrusted = untrusted)
    }

    override fun connect() {
        if (ui.setupStatus == SetupStatus.Checking) return
        when (val r = ServerAddress.normalise(ui.setupText)) {
            is AddressResult.Invalid -> ui.setupStatus = SetupStatus.Problem(ShellTexts.problem(r.problem))
            is AddressResult.Ok -> {
                ui.setupText = r.origin.url
                check(r.origin)
            }
        }
    }

    private fun check(origin: Origin) {
        ui.setupStatus = SetupStatus.Checking
        checkJob?.cancel()
        checkJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ServerCheck.run(origin, ::httpGet) }
            when (result) {
                CheckResult.Lpl -> save(origin)
                CheckResult.NotLpl -> {
                    ui.screen = Screen.Setup((ui.screen as? Screen.Setup)?.cancelable ?: (saved != null))
                    ui.setupStatus = SetupStatus.NotLpl(origin)
                }
                is CheckResult.Unreachable -> {
                    Log.i(TAG, "check: ${result.reason}")
                    ui.setupStatus = SetupStatus.Idle
                    ui.screen = Screen.Unreachable(origin.url, untrusted = result.reason.contains("certif", ignoreCase = true), checking = origin)
                }
            }
        }
    }

    override fun useAnyway(origin: Origin) = save(origin)

    private fun save(origin: Origin) {
        prefs.edit().putString(KEY_SERVER, origin.url).commit()
        val changed = saved != origin
        saved = origin
        ui.setupStatus = SetupStatus.Idle
        ui.screen = Screen.Web
        if (changed || webView.url == null) {
            clearHistoryWhenLoaded = true
            webView.loadUrl(origin.startUrl)
        }
    }

    override fun cancelSetup() {
        val sc = ui.screen as? Screen.Setup ?: return
        if (ui.setupStatus == SetupStatus.Checking) {
            checkJob?.cancel()
            ui.setupStatus = SetupStatus.Idle
            return
        }
        if (!sc.cancelable) return
        ui.setupStatus = SetupStatus.Idle
        ui.screen = screenBeforeSetup.takeIf { it !is Screen.Setup } ?: Screen.Web
    }

    override fun backToAddress() {
        val cancelable = saved != null
        ui.setupStatus = SetupStatus.Idle
        ui.screen = Screen.Setup(cancelable)
    }

    override fun tryAgain() {
        val sc = ui.screen as? Screen.Unreachable ?: return
        val origin = sc.checking
        if (origin != null) {
            ui.screen = sc.copy(retrying = true)
            checkJob?.cancel()
            checkJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { ServerCheck.run(origin, ::httpGet) }
                when (result) {
                    CheckResult.Lpl -> save(origin)
                    CheckResult.NotLpl -> {
                        ui.screen = Screen.Setup(saved != null)
                        ui.setupStatus = SetupStatus.NotLpl(origin)
                    }
                    is CheckResult.Unreachable -> ui.screen = sc.copy(retrying = false)
                }
            }
            return
        }
        val s = saved ?: return
        ui.screen = sc.copy(retrying = true)
        pageFailed = false
        webView.loadUrl(lastFailedUrl?.takeIf { Origin.of(it) == s } ?: s.startUrl)
    }

    override fun changeAddress() {
        val sc = ui.screen
        val text = if (sc is Screen.Unreachable && sc.checking != null) sc.checking.url else saved?.url ?: ""
        if (sc is Screen.Unreachable && sc.checking != null) {
            checkJob?.cancel()
            backToAddress()
            ui.setupText = text
        } else {
            showSetup(text, cancelable = saved != null)
        }
    }

    /** GET without following redirects; the body is read up to 256 KB. Throws when nothing answers. */
    private fun httpGet(url: String): HttpAnswer {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/manifest+json, application/json, text/html;q=0.9, */*;q=0.5")
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = stream?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buf.size() < 256 * 1024) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toString("UTF-8")
            } ?: ""
            return HttpAnswer(status, body)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val TAG = "LplShell"
        const val PREFS = "shell"
        const val KEY_SERVER = "server"
        private const val KEY_WEB = "web"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** The intent that starts the darktable app: built from the parsed link, nothing taken over from it. */
        fun handOffIntent(h: HandOff): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(h.data))
                .setPackage(HandOff.PACKAGE)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Another origin: the phone's default browser (or the app that handles that link). */
        fun browserIntent(url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The system file picker; several files when the page's input allows it. */
        fun fileChooserIntent(types: List<String>, multiple: Boolean): Intent =
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (types.size == 1) types[0] else "*/*"
                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
    }
}
