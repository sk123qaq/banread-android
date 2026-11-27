package app.banread.viz

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewParent
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.FallbackItem
import app.banread.model.Provenance
import app.banread.model.ReaderTheme
import app.banread.model.VizHostEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewVizHostTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `queues renderArtifact until host_ready, coalesces to latest, and supports live theme updates`() = runTest {
        val scripts = mutableListOf<String>()
        val recordingPool = VizWebViewPool(
            context = context,
            webViewFactory = { ctx ->
                object : VizTouchWebView(ctx) {
                    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                        scripts += script
                        super.evaluateJavascript(script, resultCallback)
                    }
                }
            },
            webMessageListenerSupported = { false },
        )

        val events = mutableListOf<VizHostEvent>()
        val host = WebViewVizHost(
            webViewPool = recordingPool,
            mainPoster = { it() },
        )
        host.attachWebView { events += it }

        val firstArtifact = AuditedArtifact.MarkdownReadCard(
            caption = "旧卡片",
            markdown = "旧内容 `[B01]`",
        )
        val latestArtifact = AuditedArtifact.FallbackList(
            caption = "最新降级清单",
            fallbackNotice = "已安全降级",
            items = listOf(
                FallbackItem(
                    label = "关键要点",
                    detail = "细节说明",
                    blocks = listOf(BlockId("B03")),
                    source = Provenance.SOURCE_TEXT,
                ),
            ),
        )

        // Queued before host_ready: 0 scripts dispatched yet
        host.renderArtifact(firstArtifact, ReaderTheme.PAPER_LIGHT)
        host.renderArtifact(latestArtifact, ReaderTheme.SEPIA_WARM)
        assertTrue(scripts.isEmpty())

        // host_ready flushes ONLY the latest queued artifact
        host.handleBridgeSignalForTest("""{"type":"host_ready"}""")
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().contains("window.BanReadVizHost.renderArtifact("))
        assertTrue(scripts.single().contains("最新降级清单"))
        assertFalse(scripts.single().contains("旧卡片"))

        // Theme-only update on the SAME artifact uses applyTheme without full re-render
        scripts.clear()
        host.renderArtifact(latestArtifact, ReaderTheme.EINK_HIGH_CONTRAST)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().contains("window.BanReadVizHost.applyTheme("))
        assertTrue(scripts.single().contains("eink"))

        // Same artifact + same theme is a no-op
        scripts.clear()
        host.renderArtifact(latestArtifact, ReaderTheme.EINK_HIGH_CONTRAST)
        assertTrue(scripts.isEmpty())

        // Fullscreen mode toggle dispatches setViewportMode
        host.setFullscreenMode(true)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().contains("setViewportMode(\"fullscreen\")"))

        // Bridge events dispatch cleanly to hostCallback
        val webView = recordingPool.currentWebViewOrNull()!!
        val legacyBridge = Shadows.shadowOf(webView).getJavascriptInterface(VizWebViewPool.LEGACY_BRIDGE_NAME)
        val postMsg = legacyBridge.javaClass.getDeclaredMethod("postMessage", String::class.java)
        postMsg.invoke(legacyBridge, """{"type":"jump","blockId":"B03"}""")
        postMsg.invoke(legacyBridge, """{"type":"fullscreen"}""")
        postMsg.invoke(legacyBridge, """{"type":"error","message":"boom"}""")
        postMsg.invoke(legacyBridge, "{bad json")
        postMsg.invoke(legacyBridge, """{"type":"jump","blockId":"INVALID"}""")

        assertEquals(
            listOf(
                VizHostEvent.JumpToBlock(BlockId("B03")),
                VizHostEvent.OpenFullscreen,
                VizHostEvent.Error("boom"),
            ),
            events,
        )
    }

    @Test
    fun `VizWebViewPool hardens WebSettings, serves offline viz assets, and blocks external URLs`() {
        val pool = VizWebViewPool(
            context = context,
            webMessageListenerSupported = { false },
        )
        val wv1 = pool.acquireWebView(context)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(wv1.settings.javaScriptEnabled)
        assertFalse(wv1.settings.domStorageEnabled)
        assertFalse(wv1.settings.allowFileAccess)
        assertFalse(wv1.settings.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, wv1.settings.mixedContentMode)
        assertFalse(wv1.settings.builtInZoomControls)

        assertEquals(VizWebViewPool.HOST_PAGE_URL, Shadows.shadowOf(wv1).lastLoadedUrl)

        // Verify offline WebViewClient serves assets/viz/* and returns 403 for external URLs
        val client = Shadows.shadowOf(wv1).webViewClient
        val allowedReq = fakeRequest("https://appassets.androidplatform.net/viz/mobile_viz_host.html")
        val allowedResp = client.shouldInterceptRequest(wv1, allowedReq)
        assertNotNull(allowedResp)
        assertTrue(allowedResp!!.data != null)

        val diagramJsReq = fakeRequest("https://appassets.androidplatform.net/viz/diagram_renderer.js")
        val diagramJsResp = client.shouldInterceptRequest(wv1, diagramJsReq)
        assertNotNull(diagramJsResp)

        val externalHttpResp = client.shouldInterceptRequest(wv1, fakeRequest("https://evil.example.com/payload.js"))
        assertEquals(403, externalHttpResp?.statusCode)

        val fileSchemeResp = client.shouldInterceptRequest(wv1, fakeRequest("file:///etc/passwd"))
        assertEquals(403, fileSchemeResp?.statusCode)

        // Pooling reuses same WebView across attach/detach
        val container = FrameLayout(context)
        container.addView(wv1)
        assertEquals(1, container.childCount)
        pool.detachWebView()
        assertEquals(0, container.childCount)

        val wv2 = pool.acquireWebView(context)
        assertSame(wv1, wv2)
        pool.release()
    }

    @Test
    fun `VizTouchWebView requests parent disallow intercept on down or move and releases on up or cancel`() {
        var disallowed: Boolean? = null
        val trackingParent = object : FrameLayout(context) {
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                disallowed = disallowIntercept
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
            }
        }
        val touchWebView = VizTouchWebView(context)
        trackingParent.addView(touchWebView)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        touchWebView.onTouchEvent(down)
        assertEquals(true, disallowed)
        down.recycle()

        disallowed = null
        val move = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_MOVE, 25f, 15f, 0)
        touchWebView.onTouchEvent(move)
        assertEquals(true, disallowed)
        move.recycle()

        val up = MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_UP, 25f, 15f, 0)
        touchWebView.onTouchEvent(up)
        assertEquals(false, disallowed)
        up.recycle()

        val down2 = MotionEvent.obtain(0L, 30L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        touchWebView.dispatchTouchEvent(down2)
        assertEquals(true, disallowed)
        down2.recycle()

        val cancel = MotionEvent.obtain(0L, 40L, MotionEvent.ACTION_CANCEL, 10f, 10f, 0)
        touchWebView.dispatchTouchEvent(cancel)
        assertEquals(false, disallowed)
        cancel.recycle()
    }

    @Test
    fun `regression Bug 1 - attachWebView does not detach already-mounted pooled WebView`() {
        val pool = VizWebViewPool(
            context = context,
            webMessageListenerSupported = { false },
        )
        val host = WebViewVizHost(
            webViewPool = pool,
            mainPoster = { it() },
        )

        val container = FrameLayout(context)
        val wv = host.acquireWebView(context)
        container.addView(wv)
        assertEquals(1, container.childCount)
        assertSame(wv, container.getChildAt(0))

        host.attachWebView { }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, container.childCount)
        assertSame(wv, container.getChildAt(0))
        pool.release()
    }

    @Test
    fun `regression Bug 2 - retrying renderArtifact with same artifact after JS error dispatches fresh render`() = runTest {
        val scripts = mutableListOf<String>()
        val recordingPool = VizWebViewPool(
            context = context,
            webViewFactory = { ctx ->
                object : VizTouchWebView(ctx) {
                    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                        scripts += script
                        super.evaluateJavascript(script, resultCallback)
                    }
                }
            },
            webMessageListenerSupported = { false },
        )
        val events = mutableListOf<VizHostEvent>()
        val host = WebViewVizHost(
            webViewPool = recordingPool,
            mainPoster = { it() },
        )
        host.attachWebView { events += it }
        host.handleBridgeSignalForTest("""{"type":"host_ready"}""")

        val artifact = AuditedArtifact.MarkdownReadCard(
            caption = "重试测试卡",
            markdown = "内容 `[B07]`",
        )

        host.renderArtifact(artifact, ReaderTheme.PAPER_LIGHT)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().contains("window.BanReadVizHost.renderArtifact("))

        scripts.clear()
        host.handleBridgeSignalForTest("""{"type":"error","message":"transient"}""")
        assertEquals(listOf(VizHostEvent.Error("transient")), events)

        host.renderArtifact(artifact, ReaderTheme.PAPER_LIGHT)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().contains("window.BanReadVizHost.renderArtifact("))
    }

    private fun fakeRequest(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
