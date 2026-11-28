package app.banread.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.banread.ai.AiRuntimeFactory
import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.companion.CompanionEngineFactory
import app.banread.library.LibraryVaultFactory
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BookRendererPort
import app.banread.model.DeviceId
import app.banread.model.NoteLogEntry
import app.banread.model.ReadingAnchor
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.ReaderEngineFactory
import app.banread.sync.internal.ShareAesGcmCrypto
import app.banread.sync.internal.SyncEndpointUrlPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEndpointUrlPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `allows cleartext http for RFC1918 LAN, Tailscale CGNAT, loopback, local domains, and private IPv6`() {
        val allowedCleartext = listOf(
            "http://192.168.0.1:8780",
            "http://192.168.1.50:8780/",
            "http://192.168.255.254:8780",
            "http://10.0.0.1:8780",
            "http://10.0.2.2:8780",
            "http://10.255.255.254:8780",
            "http://172.16.0.1:8780",
            "http://172.24.12.8:8780",
            "http://172.31.255.254:8780",
            "http://100.64.0.1:8780",
            "http://100.100.100.100:8780",
            "http://100.127.255.254:8780",
            "http://127.0.0.1:8780",
            "http://127.0.1.1:8780",
            "http://localhost:8780",
            "http://[::1]:8780",
            "http://[fd7a:115c:a1e0::1]:8780",
            "http://desktop.local:8780",
            "http://nas.lan:5005/dav",
            "http://gateway.home.arpa:8780",
        )

        for (raw in allowedCleartext) {
            val normalized = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(raw)
            assertEquals(raw.trimEnd('/'), normalized)
        }
    }

    @Test
    fun `rejects cleartext http for all public domains, public IPs, boundary-adjacent IPs, and spoofed hosts`() {
        val rejectedCleartext = listOf(
            "http://example.com",
            "http://api.banread.app:8780",
            "http://dav.example.com/banread",
            "http://my-desktop.ts.net:8780",
            "http://evil.local.example.com:8780",
            "http://127.0.0.1.evil.com:8780",
            "http://127.0.0.1@evil.example.com:8780",
            "http://8.8.8.8:8780",
            "http://1.1.1.1:8780",
            "http://0x08080808:8780",
            "http://192.169.0.1:8780",
            "http://172.15.255.255:8780",
            "http://172.32.0.1:8780",
            "http://100.63.255.255:8780",
            "http://100.128.0.1:8780",
            "http://[2001:4860:4860::8888]:8780",
            "http://[::ffff:8.8.8.8]:8780",
            "ftp://192.168.1.10/book",
            "not-a-url",
        )

        for (raw in rejectedCleartext) {
            val res = runCatching { SyncEndpointUrlPolicy.normalizeHttpBaseUrl(raw) }
            assertTrue("Expected cleartext/invalid URL to be rejected: $raw", res.isFailure)
            val err = res.exceptionOrNull()
            assertTrue("Expected AppFailureException for $raw, got $err", err is AppFailureException)
            assertTrue(
                "Expected NetworkOrSyncConflict for $raw",
                (err as AppFailureException).failure is AppFailure.NetworkOrSyncConflict,
            )
        }
    }

    @Test
    fun `allows https for public domains and public IPs`() {
        val allowedHttps = listOf(
            "https://example.com/",
            "https://dav.example.com/banread/",
            "https://r2.cloudflarestorage.com",
            "https://8.8.8.8:8443",
            "https://my-desktop.ts.net",
        )
        for (raw in allowedHttps) {
            assertEquals(raw.trimEnd('/'), SyncEndpointUrlPolicy.normalizeHttpBaseUrl(raw))
        }
    }

    @Test
    fun `isPrivateOrLoopbackHost enforces exact CIDR boundaries`() {
        assertTrue(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("100.64.0.0"))
        assertTrue(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("100.127.255.255"))
        assertFalse(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("100.63.255.255"))
        assertFalse(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("100.128.0.0"))
        assertTrue(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("172.16.0.1"))
        assertTrue(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("172.31.255.254"))
        assertFalse(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("172.15.255.255"))
        assertFalse(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("172.32.0.0"))
        assertFalse(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost(".local"))
        assertFalse(SyncEndpointUrlPolicy.isPrivateOrLoopbackHost("..local"))
    }

    @Test
    fun `PairingUriParser, HttpDesktopGatewayAdapter, WebDavShardAdapter, S3R2ShardAdapter, and ShareCoordinator enforce HTTPS on public hosts`() = runTest {
        val vault = InMemorySecretVaultAdapter().apply {
            putSecret("dav_secret", "pass")
            putSecret("s3_secret", "secret")
        }

        // 1. PairingUriParser: allows LAN/Tailscale HTTP & public HTTPS, rejects public HTTP
        assertEquals(
            "http://192.168.1.10:8780",
            PairingUriParser.parse("bc-pair://v1?base=http://192.168.1.10:8780&token=tok1").baseUrl,
        )
        assertEquals(
            "http://100.88.1.2:8780",
            PairingUriParser.parse("bc-pair://v1?base=http://100.88.1.2:8780&token=tok2").baseUrl,
        )
        assertEquals(
            "https://gateway.example.com",
            PairingUriParser.parse("bc-pair://v1?base=https://gateway.example.com&token=tok3").baseUrl,
        )
        assertTrue(runCatching {
            PairingUriParser.parse("bc-pair://v1?base=http://gateway.example.com:8780&token=tok4")
        }.isFailure)
        assertTrue(runCatching {
            PairingUriParser.parse("bc-pair://v1?base=http://8.8.8.8:8780&token=tok5")
        }.isFailure)

        // 2. HttpDesktopGatewayAdapter direct calls reject public HTTP before network IO
        val gateway = HttpDesktopGatewayAdapter()
        val gwErr = runCatching { gateway.checkHealth("tok", "http://public.example.com:8780") }
        assertTrue(gwErr.isFailure)
        assertTrue(gwErr.exceptionOrNull()!!.message!!.contains("https://"))

        // 3. WebDavShardAdapter rejects public HTTP, allows LAN/Tailscale HTTP & public HTTPS
        assertTrue(runCatching {
            WebDavShardAdapter(WebDavConfig("http://dav.example.com/banread", "u"), vault, "dav_secret")
        }.isFailure)
        WebDavShardAdapter(WebDavConfig("http://192.168.1.20:5005/dav", "u"), vault, "dav_secret")
        WebDavShardAdapter(WebDavConfig("http://100.64.2.3:5005/dav", "u"), vault, "dav_secret")
        WebDavShardAdapter(WebDavConfig("https://dav.example.com/banread", "u"), vault, "dav_secret")

        // 4. S3R2ShardAdapter rejects public HTTP, allows LAN/Tailscale HTTP & public HTTPS
        assertTrue(runCatching {
            S3R2ShardAdapter(S3R2Config("http://r2.example.com", "bkt", "auto", "AKIA"), vault, "s3_secret")
        }.isFailure)
        S3R2ShardAdapter(S3R2Config("http://192.168.1.20:9000", "bkt", "auto", "AKIA"), vault, "s3_secret")
        S3R2ShardAdapter(S3R2Config("http://100.127.0.1:9000", "bkt", "auto", "AKIA"), vault, "s3_secret")
        S3R2ShardAdapter(S3R2Config("https://r2.example.com", "bkt", "auto", "AKIA"), vault, "s3_secret")

        // 5. ShareCoordinator & ShareAesGcmCrypto reject public HTTP on both R2 relay and LAN receive links
        val context: Context = ApplicationProvider.getApplicationContext()
        val root = tempFolder.newFolder("policy_share")
        val registry = ProfileRegistry.loadFromClasspath()
        val libVault = LibraryVaultFactory.create(context, root, registry, allowInMemory = true)
        val reader = ReaderEngineFactory.create(context, root, libVault, NoOpRenderer(), DeviceId("d1"), allowInMemory = true)
        val ai = AiRuntimeFactory.createInMemory(configDir = File(root, "ai"))
        val companion = CompanionEngineFactory.create(libVault, ai, registry, null, context, root, allowInMemoryDb = true)
        val share = SyncCoordinatorFactory.createShareCoordinator(context, root, libVault, reader, companion, vault, DeviceId("d1"))
        try {
            val r2Bad = runCatching { share.configureR2Relay("http://share.example.com") }
            assertTrue(r2Bad.isFailure)
            share.configureR2Relay("https://share.example.com")
            share.configureR2Relay("http://192.168.1.20:8787")

            val keyFrag = ShareAesGcmCrypto.encodeKeyBase64Url(ShareAesGcmCrypto.generateKey())
            val r2LinkBad = share.previewIncomingShare("http://share.example.com/s/br_test01#k=$keyFrag")
            assertTrue(r2LinkBad.isFailure)
            assertTrue(r2LinkBad.exceptionOrNull()!!.message!!.contains("https://"))

            val lanLinkBad = share.previewIncomingShare("http://evil.example.com:9721/share/v1?code=123456")
            assertTrue(lanLinkBad.isFailure)
            assertTrue(lanLinkBad.exceptionOrNull()!!.message!!.contains("https://"))
        } finally {
            share.close()
            (companion as? Closeable)?.close()
            (reader as? Closeable)?.close()
            (libVault as? Closeable)?.close()
        }
    }

    @Test
    fun `defaultHttpClient network interceptor blocks HTTP redirect downgrade to public cleartext host`() {
        val redirectServer = TestLoopbackHttpServer {
            TestHttpResponse(
                code = 302,
                bodyBytes = ByteArray(0),
                headers = mapOf("Location" to "http://public-downgrade.example.com/secret"),
            )
        }
        try {
            val client = HttpDesktopGatewayAdapter.defaultHttpClient()
            val req = Request.Builder().url("${redirectServer.baseUrl}/start").get().build()
            val res = runCatching { client.newCall(req).execute().use { it.code } }
            assertTrue("Expected redirect to public http:// host to be blocked", res.isFailure)
            assertTrue(res.exceptionOrNull() is IOException)
            assertTrue(res.exceptionOrNull()!!.message!!.contains("https://"))
        } finally {
            redirectServer.close()
        }
    }

    private class NoOpRenderer : BookRendererPort {
        override val locationEvents: Flow<ReadingAnchor> = MutableSharedFlow()
        override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) = Unit
        override suspend fun navigate(anchor: ReadingAnchor) = Unit
        override suspend fun updateStyles(cssPayload: String) = Unit
        override suspend fun renderDecorations(annotations: List<NoteLogEntry>) = Unit
    }
}
