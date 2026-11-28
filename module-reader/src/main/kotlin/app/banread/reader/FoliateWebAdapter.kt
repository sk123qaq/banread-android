package app.banread.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import app.banread.model.BookRendererPort
import app.banread.model.NoteLogEntry
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.reader.internal.JsAnchorPayload
import app.banread.reader.internal.JsAnnotationPayload
import app.banread.reader.internal.JsFootnoteClickPayload
import app.banread.reader.internal.JsRelocatePayload
import app.banread.reader.internal.JsSelectionPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * WebView-backed [BookRendererPort] driving the offline es2020-transpiled `foliate-js` bundle
 * via `WebViewAssetLoader` on `https://appassets.androidplatform.net/`.
 */
class FoliateWebAdapter(
    context: Context,
    private val filesRoot: File,
) : BookRendererPort {

    private val appContext = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _locationEvents = MutableSharedFlow<ReadingAnchor>(extraBufferCapacity = 32)
    override val locationEvents: Flow<ReadingAnchor> = _locationEvents.asSharedFlow()

    private val _footnoteEvents = MutableSharedFlow<FootnoteClickEvent>(extraBufferCapacity = 16)
    val footnoteEvents: Flow<FootnoteClickEvent> = _footnoteEvents.asSharedFlow()

    private val _selectionEvents = MutableSharedFlow<TextSelectionEvent>(extraBufferCapacity = 16)
    val selectionEvents: Flow<TextSelectionEvent> = _selectionEvents.asSharedFlow()

    private val pendingScripts = ConcurrentLinkedQueue<String>()
    private val scriptLock = Any()

    @Volatile
    private var attachedWebView: WebView? = null

    /**
     * 当前 attached WebView 是否已 detach/destroy。置位后：
     * - 已 post 到主线程的 evaluateJavascript 闭包直接丢弃，不碰已 destroy 的 WebView；
     * - JS bridge 回调不再生效（旧 WebView 的 onBridgeReady 不会把脚本 flush 到新 WebView）。
     * attachWebView() 会重置该标志（adapter 是单例，阅读器可反复进出）。
     */
    @Volatile
    private var destroyed = false

    @Volatile
    private var bridgeReady = false

    @Volatile
    private var requestedPaginationMode = PaginationMode.SLIDE

    @Volatile
    private var activeTheme = ReaderTheme.PAPER_LIGHT

    @Volatile
    private var lastCssPayload = ""

    @Volatile
    private var horizontalMarginDp = 20f

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
        .addPathHandler("/user-fonts/", CanonicalDirPathHandler(listOf(File(filesRoot, "fonts"))))
        .addPathHandler(
            "/books/",
            CanonicalDirPathHandler(listOf(File(filesRoot, "compiled"), File(filesRoot, "books"))),
        )
        .build()

    fun checkWebViewPackage(): WebViewPackageStatus {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(appContext) }.getOrNull()
        return WebViewPackageStatus(
            packageName = pkg?.packageName,
            versionName = pkg?.versionName,
            isAvailable = pkg != null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun attachWebView(webView: WebView) {
        synchronized(scriptLock) {
            destroyed = false
            attachedWebView = webView
            bridgeReady = false
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.addJavascriptInterface(NativeJsBridge(webView), "BanReadNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(url)
            }
        }
        webView.loadUrl(HOST_PAGE_URL)
    }

    fun detachWebView() {
        // 先置 destroyed：此后所有已 post/在飞的 evaluateJavascript 闭包都会自行丢弃。
        val webView: WebView?
        synchronized(scriptLock) {
            destroyed = true
            webView = attachedWebView
            attachedWebView = null
            bridgeReady = false
            pendingScripts.clear()
        }
        mainHandler.removeCallbacksAndMessages(null)
        webView?.removeJavascriptInterface("BanReadNative")
    }

    fun setPaginationMode(mode: PaginationMode) {
        requestedPaginationMode = mode
        val effective = ThemePalette.effectivePaginationMode(activeTheme, mode)
        if (lastCssPayload.isNotEmpty()) {
            evaluateBridge(
                "window.BanReadBridge && window.BanReadBridge.updateStyles(${JSONObject.quote(lastCssPayload)}, ${JSONObject.quote(effective.name)}, $horizontalMarginDp);",
            )
        }
    }

    internal fun setThemeContext(theme: ReaderTheme) {
        activeTheme = theme
    }

    internal fun setHorizontalMarginDp(marginDp: Float) {
        horizontalMarginDp = marginDp.coerceAtLeast(0f)
    }

    fun prevPage() {
        evaluateBridge("window.BanReadBridge && window.BanReadBridge.prevPage();")
    }

    fun nextPage() {
        evaluateBridge("window.BanReadBridge && window.BanReadBridge.nextPage();")
    }

    override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) {
        val anchorArg = initialAnchor?.let {
            JSONObject.quote(json.encodeToString(JsAnchorPayload.serializer(), JsAnchorPayload.fromDomain(it)))
        } ?: "null"
        evaluateBridge(
            "window.BanReadBridge && window.BanReadBridge.openBook(${JSONObject.quote(compiledAssetRoot)}, $anchorArg);",
        )
    }

    override suspend fun navigate(anchor: ReadingAnchor) {
        navigateWithPulse(anchor, highlightPulseMs = 0L)
    }

    fun navigateWithPulse(anchor: ReadingAnchor, highlightPulseMs: Long) {
        val anchorJson = json.encodeToString(JsAnchorPayload.serializer(), JsAnchorPayload.fromDomain(anchor))
        evaluateBridge(
            "window.BanReadBridge && window.BanReadBridge.goToAnchor(${JSONObject.quote(anchorJson)}, $highlightPulseMs);",
        )
    }

    override suspend fun updateStyles(cssPayload: String) {
        lastCssPayload = cssPayload
        val effectiveMode = ThemePalette.effectivePaginationMode(activeTheme, requestedPaginationMode)
        evaluateBridge(
            "window.BanReadBridge && window.BanReadBridge.updateStyles(${JSONObject.quote(cssPayload)}, ${JSONObject.quote(effectiveMode.name)}, $horizontalMarginDp);",
        )
    }

    override suspend fun renderDecorations(annotations: List<NoteLogEntry>) {
        val payload = annotations.map { JsAnnotationPayload.fromDomain(it) }
        val encoded = json.encodeToString(ListSerializer(JsAnnotationPayload.serializer()), payload)
        evaluateBridge(
            "window.BanReadBridge && window.BanReadBridge.renderDecorations(${JSONObject.quote(encoded)});",
        )
    }

    private fun evaluateBridge(script: String) {
        val webViewToRun: WebView? = synchronized(scriptLock) {
            val webView = attachedWebView
            if (webView == null || !bridgeReady) {
                // 没有可执行的 WebView：入队，等 attach + onBridgeReady 后统一 flush（首开/重进阅读器都走这里）。
                pendingScripts.add(script)
                null
            } else {
                webView
            }
        }
        if (webViewToRun != null) {
            runOnMain {
                if (!isLive(webViewToRun)) return@runOnMain
                webViewToRun.evaluateJavascript(script, null)
            }
        }
    }

    private fun flushPendingScriptsOnMain(webView: WebView) {
        val queued = synchronized(scriptLock) {
            if (!isLive(webView)) return
            bridgeReady = true
            buildList {
                while (true) {
                    add(pendingScripts.poll() ?: break)
                }
            }
        }
        for (next in queued) {
            if (!isLive(webView)) return
            webView.evaluateJavascript(next, null)
        }
    }

    /** destroyed 与 attachedWebView 必须同时满足，才允许对该 WebView 求值/回调。 */
    private fun isLive(webView: WebView): Boolean = !destroyed && attachedWebView === webView

    private fun runOnMain(block: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post {
                if (destroyed) return@post
                block()
            }
        }
    }

    private inner class NativeJsBridge(
        private val webView: WebView,
    ) {
        @JavascriptInterface
        fun onBridgeReady(unused: String) {
            mainHandler.post {
                flushPendingScriptsOnMain(webView)
            }
        }

        @JavascriptInterface
        fun onRelocated(payloadJson: String) {
            if (!isLive(webView)) return
            runCatching {
                val parsed = json.decodeFromString(JsRelocatePayload.serializer(), payloadJson)
                _locationEvents.tryEmit(parsed.toDomain())
            }
        }

        @JavascriptInterface
        fun onFootnoteClicked(payloadJson: String) {
            if (!isLive(webView)) return
            runCatching {
                val parsed = json.decodeFromString(JsFootnoteClickPayload.serializer(), payloadJson)
                _footnoteEvents.tryEmit(parsed.toDomain())
            }
        }

        @JavascriptInterface
        fun onSelectionChanged(payloadJson: String) {
            if (!isLive(webView)) return
            runCatching {
                val parsed = json.decodeFromString(JsSelectionPayload.serializer(), payloadJson)
                _selectionEvents.tryEmit(parsed.toDomain())
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            if (!isLive(webView)) return
            // 只记录错误，不抛异常，避免拖垮 WebView renderer
            Log.e(TAG, "JS error: $message")
        }
    }

    private class CanonicalDirPathHandler(
        private val roots: List<File>,
    ) : WebViewAssetLoader.PathHandler {

        override fun handle(path: String): WebResourceResponse? {
            val cleanRel = path.trimStart('/')
            if (cleanRel.isEmpty() || cleanRel.contains("..")) return null

            for (root in roots) {
                val rootCanonical = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: continue
                val candidate = File(root, cleanRel)
                val candCanonical = runCatching { candidate.canonicalFile.toPath() }.getOrNull() ?: continue
                if (!candCanonical.startsWith(rootCanonical) || !candidate.isFile) continue
                val mime = guessMimeType(candidate.name)
                return WebResourceResponse(mime, "UTF-8", FileInputStream(candidate))
            }
            return null
        }

        private fun guessMimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "xhtml" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "opf", "xml", "ncx" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "ttc" -> "font/collection"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "epub" -> "application/epub+zip"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "BanReadReader"
        const val HOST_PAGE_URL = "https://appassets.androidplatform.net/assets/reader/reader_host.html"
    }
}
