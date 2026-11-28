package app.banread.viz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.banread.model.AuditedArtifact
import app.banread.model.ReaderTheme
import app.banread.model.VizHost
import app.banread.model.VizHostEvent
import org.json.JSONObject

/**
 * Production [VizHost] backed by a single pooled offline [WebView] in [VizWebViewPool].
 * Queues the latest [renderArtifact] until `host_ready` arrives and supports live theme-only updates.
 */
class WebViewVizHost(
    private val webViewPool: VizWebViewPool,
    private val mainPoster: ((() -> Unit) -> Unit)? = null,
) : VizHost {

    constructor(context: Context) : this(VizWebViewPool(context))

    private data class PendingRender(
        val artifact: AuditedArtifact,
        val theme: ReaderTheme,
    )

    private val lock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var hostCallback: ((VizHostEvent) -> Unit)? = null

    @Volatile
    private var hostReady: Boolean = false

    private var pendingRender: PendingRender? = null
    private var lastRenderedArtifact: AuditedArtifact? = null
    private var lastRenderedTheme: ReaderTheme? = null
    private var viewportMode: VizViewportMode = VizViewportMode.COMPACT

    override fun attachWebView(hostCallback: (VizHostEvent) -> Unit) {
        this.hostCallback = hostCallback
        webViewPool.setMessageHandler(::onRawBridgeMessage)
        postToMain {
            webViewPool.ensureInitialized()
        }
    }

    fun acquireWebView(context: Context): WebView {
        webViewPool.setMessageHandler(::onRawBridgeMessage)
        return webViewPool.acquireWebView(context)
    }

    fun detachWebView() {
        webViewPool.detachWebView()
    }

    fun setFullscreenMode(enabled: Boolean) {
        val nextMode = if (enabled) VizViewportMode.FULLSCREEN else VizViewportMode.COMPACT
        val shouldDispatch: Boolean
        synchronized(lock) {
            viewportMode = nextMode
            shouldDispatch = hostReady
        }
        if (shouldDispatch) {
            dispatchViewportMode(nextMode)
        }
    }

    override suspend fun renderArtifact(artifact: AuditedArtifact, theme: ReaderTheme) {
        val action: DispatchAction
        synchronized(lock) {
            if (!hostReady) {
                pendingRender = PendingRender(artifact, theme)
                action = DispatchAction.None
            } else if (lastRenderedArtifact == artifact) {
                if (lastRenderedTheme != theme) {
                    lastRenderedTheme = theme
                    action = DispatchAction.ThemeOnly(theme)
                } else {
                    action = DispatchAction.None
                }
            } else {
                lastRenderedArtifact = artifact
                lastRenderedTheme = theme
                action = DispatchAction.FullRender(artifact, theme)
            }
        }
        executeAction(action)
    }

    suspend fun updateTheme(theme: ReaderTheme) {
        val action: DispatchAction
        synchronized(lock) {
            if (!hostReady) {
                pendingRender = pendingRender?.copy(theme = theme)
                lastRenderedTheme = theme
                action = DispatchAction.None
            } else {
                lastRenderedTheme = theme
                action = DispatchAction.ThemeOnly(theme)
            }
        }
        executeAction(action)
    }

    fun release() {
        synchronized(lock) {
            hostReady = false
            pendingRender = null
            lastRenderedArtifact = null
            lastRenderedTheme = null
        }
        hostCallback = null
        webViewPool.setMessageHandler(null)
        postToMain {
            webViewPool.release()
        }
    }

    internal fun handleBridgeSignalForTest(rawJson: String) {
        onRawBridgeMessage(rawJson)
    }

    private fun onRawBridgeMessage(rawJson: String) {
        when (val signal = VizBridgeMessageParser.parse(rawJson)) {
            VizBridgeSignal.HostReady -> flushOnHostReady()
            is VizBridgeSignal.RenderReady -> dispatchHostEvent(
                VizHostEvent.Rendered(widthPx = signal.width, heightPx = signal.height),
            )
            is VizBridgeSignal.Event -> {
                if (signal.hostEvent is VizHostEvent.Error) {
                    synchronized(lock) {
                        lastRenderedArtifact = null
                    }
                }
                dispatchHostEvent(signal.hostEvent)
            }
            is VizBridgeSignal.Ignored -> Unit
        }
    }

    private fun flushOnHostReady() {
        val pending: PendingRender?
        val modeToApply: VizViewportMode
        val themeOnlyToApply: ReaderTheme?
        synchronized(lock) {
            hostReady = true
            pending = pendingRender
            pendingRender = null
            modeToApply = viewportMode
            if (pending != null) {
                lastRenderedArtifact = pending.artifact
                lastRenderedTheme = pending.theme
                themeOnlyToApply = null
            } else {
                themeOnlyToApply = lastRenderedTheme
            }
        }

        if (modeToApply != VizViewportMode.COMPACT) {
            dispatchViewportMode(modeToApply)
        }
        if (pending != null) {
            dispatchFullRender(pending.artifact, pending.theme)
        } else if (themeOnlyToApply != null) {
            dispatchThemeOnly(themeOnlyToApply)
        }
    }

    private fun executeAction(action: DispatchAction) {
        when (action) {
            DispatchAction.None -> Unit
            is DispatchAction.ThemeOnly -> dispatchThemeOnly(action.theme)
            is DispatchAction.FullRender -> dispatchFullRender(action.artifact, action.theme)
        }
    }

    private fun dispatchFullRender(artifact: AuditedArtifact, theme: ReaderTheme) {
        val artifactJson = VizPayloadSerializer.serializeArtifact(artifact)
        val themeJson = VizThemeTokens.toJson(theme)
        val script =
            "window.BanReadVizHost && window.BanReadVizHost.renderArtifact(" +
                "${JSONObject.quote(artifactJson)}, ${JSONObject.quote(themeJson)});"
        postToMain {
            webViewPool.evaluateJavascript(script)
        }
    }

    private fun dispatchThemeOnly(theme: ReaderTheme) {
        val themeJson = VizThemeTokens.toJson(theme)
        val script =
            "window.BanReadVizHost && window.BanReadVizHost.applyTheme(${JSONObject.quote(themeJson)});"
        postToMain {
            webViewPool.evaluateJavascript(script)
        }
    }

    private fun dispatchViewportMode(mode: VizViewportMode) {
        val script =
            "window.BanReadVizHost && window.BanReadVizHost.setViewportMode(${JSONObject.quote(mode.wireValue)});"
        postToMain {
            webViewPool.evaluateJavascript(script)
        }
    }

    private fun dispatchHostEvent(event: VizHostEvent) {
        val cb = hostCallback ?: return
        postToMain {
            cb.invoke(event)
        }
    }

    private fun postToMain(block: () -> Unit) {
        val customPoster = mainPoster
        if (customPoster != null) {
            customPoster(block)
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private sealed interface DispatchAction {
        data object None : DispatchAction
        data class ThemeOnly(val theme: ReaderTheme) : DispatchAction
        data class FullRender(val artifact: AuditedArtifact, val theme: ReaderTheme) : DispatchAction
    }
}
