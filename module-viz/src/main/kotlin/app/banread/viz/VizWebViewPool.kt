package app.banread.viz

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

enum class VizViewportMode(val wireValue: String) {
    COMPACT("compact"),
    FULLSCREEN("fullscreen"),
}

/**
 * Manages a single hardened, offline-only pooled [WebView] serving `assets/viz/`
 * via [WebViewAssetLoader] at `https://appassets.androidplatform.net/viz/mobile_viz_host.html`.
 */
class VizWebViewPool(
    context: Context,
    private val webViewFactory: (Context) -> WebView = { ctx -> VizTouchWebView(ctx) },
    private val webMessageListenerSupported: () -> Boolean = {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    },
) {
    private val appContext: Context = context.applicationContext ?: context
    private val contextWrapper = MutableContextWrapper(appContext)

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/viz/", VizAssetsPathHandler(appContext))
        .build()

    @Volatile
    private var pooledWebView: WebView? = null

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var messageHandler: ((String) -> Unit)? = null

    internal fun setMessageHandler(handler: ((String) -> Unit)?) {
        messageHandler = handler
    }

    fun acquireWebView(context: Context): WebView {
        contextWrapper.baseContext = context
        val existing = pooledWebView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val created = webViewFactory(contextWrapper)
        pooledWebView = created
        configureAndLoad(created)
        return created
    }

    internal fun ensureInitialized(): WebView {
        val existing = pooledWebView
        if (existing != null) {
            return existing
        }
        return acquireWebView(appContext)
    }

    fun detachWebView() {
        val existing = pooledWebView ?: return
        (existing.parent as? ViewGroup)?.removeView(existing)
        contextWrapper.baseContext = appContext
    }

    fun release() {
        val existing = pooledWebView ?: return
        (existing.parent as? ViewGroup)?.removeView(existing)
        runCatching {
            existing.removeJavascriptInterface(LEGACY_BRIDGE_NAME)
            if (webMessageListenerSupported()) {
                WebViewCompat.removeWebMessageListener(existing, BRIDGE_NAME)
            }
            existing.destroy()
        }
        pooledWebView = null
        initialized = false
        contextWrapper.baseContext = appContext
    }

    internal fun currentWebViewOrNull(): WebView? = pooledWebView

    internal fun evaluateJavascript(script: String) {
        pooledWebView?.evaluateJavascript(script, null)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureAndLoad(webView: WebView) {
        if (initialized) return
        initialized = true

        webView.setBackgroundColor(Color.TRANSPARENT)
        configureHardenedSettings(webView.settings)
        installBridge(webView)
        webView.webViewClient = OfflineOnlyWebViewClient(assetLoader)
        webView.loadUrl(HOST_PAGE_URL)
    }

    private fun configureHardenedSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
    }

    @SuppressLint("JavascriptInterface")
    private fun installBridge(webView: WebView) {
        if (webMessageListenerSupported()) {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                setOf(APP_ASSETS_ORIGIN),
            ) { _, message, _, _, _ ->
                message.data?.let { raw -> messageHandler?.invoke(raw) }
            }
        } else {
            webView.addJavascriptInterface(LegacyVizJsBridge(), LEGACY_BRIDGE_NAME)
        }
    }

    private inner class LegacyVizJsBridge {
        @JavascriptInterface
        fun postMessage(jsonPayload: String?) {
            if (jsonPayload != null) {
                messageHandler?.invoke(jsonPayload)
            }
        }
    }

    private class VizAssetsPathHandler(context: Context) : WebViewAssetLoader.PathHandler {
        private val delegate = WebViewAssetLoader.AssetsPathHandler(context)

        override fun handle(path: String): WebResourceResponse? {
            val clean = path.trimStart('/')
            if (clean.isEmpty() || clean.contains("..")) return null
            return delegate.handle("viz/$clean")
        }
    }

    private class OfflineOnlyWebViewClient(
        private val assetLoader: WebViewAssetLoader,
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse {
            val uri = request?.url ?: return forbiddenResponse()
            val isAllowedHost = uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(WebViewAssetLoader.DEFAULT_DOMAIN, ignoreCase = true)
            if (!isAllowedHost) {
                return forbiddenResponse()
            }
            return assetLoader.shouldInterceptRequest(uri) ?: forbiddenResponse()
        }

        private fun forbiddenResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    companion object {
        const val APP_ASSETS_ORIGIN = "https://appassets.androidplatform.net"
        const val HOST_PAGE_URL = "$APP_ASSETS_ORIGIN/viz/mobile_viz_host.html"
        const val BRIDGE_NAME = "BanReadVizBridge"
        const val LEGACY_BRIDGE_NAME = "BanReadVizLegacy"
    }
}
