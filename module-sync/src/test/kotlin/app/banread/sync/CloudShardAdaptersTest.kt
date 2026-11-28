package app.banread.sync

import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.StateSyncPort
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CloudShardAdaptersTest {

    private lateinit var server: TestLoopbackHttpServer
    private lateinit var baseUrl: String
    private val storedObjects = ConcurrentHashMap<String, ByteArray>()
    private val recordedRequests = CopyOnWriteArrayList<RecordedHttpCall>()
    private var simulateRateLimit429 = false

    @Before
    fun setUp() {
        server = TestLoopbackHttpServer { req ->
            val method = req.method
            val path = req.path
            val query = req.rawQuery
            val auth = req.headers["authorization"].orEmpty()
            val body = req.bodyBytes
            recordedRequests += RecordedHttpCall(
                method = method,
                path = path,
                query = query,
                authHeader = auth,
                amzDate = req.headers["x-amz-date"].orEmpty(),
                amzSha256 = req.headers["x-amz-content-sha256"].orEmpty(),
            )

            if (simulateRateLimit429) {
                return@TestLoopbackHttpServer TestHttpResponse(429, ByteArray(0), mapOf("Retry-After" to "30"))
            }

            if (path.startsWith("/dav")) {
                val expectedBasic = "Basic dXNlcjpwYXNzMTIz"
                if (auth != expectedBasic && auth != "Bearer dav-token") {
                    return@TestLoopbackHttpServer TestHttpResponse(401)
                }
                val rel = path.removePrefix("/dav").trim('/')
                return@TestLoopbackHttpServer when (method) {
                    "MKCOL" -> TestHttpResponse(201)
                    "PUT" -> {
                        storedObjects[rel] = body
                        TestHttpResponse(201)
                    }
                    "GET" -> {
                        val data = storedObjects[rel]
                        if (data == null) TestHttpResponse(404) else TestHttpResponse(200, data)
                    }
                    "PROPFIND" -> {
                        val prefix = rel.trimEnd('/') + "/"
                        val matches = storedObjects.keys.filter { it.startsWith(prefix) }.sorted()
                        if (matches.isEmpty()) {
                            TestHttpResponse(404)
                        } else {
                            val entriesXml = matches.joinToString("") { k -> "<d:response><d:href>/dav/$k</d:href></d:response>" }
                            val xml = """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/$prefix</d:href></d:response>$entriesXml</d:multistatus>"""
                            TestHttpResponse(207, xml.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/xml; charset=utf-8"))
                        }
                    }
                    else -> TestHttpResponse(405)
                }
            }

            if (path.startsWith("/banread-bucket")) {
                if (!auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIA123456/")) {
                    return@TestLoopbackHttpServer TestHttpResponse(403)
                }
                val key = path.removePrefix("/banread-bucket").trimStart('/')
                return@TestLoopbackHttpServer when {
                    method == "PUT" -> {
                        storedObjects["s3/$key"] = body
                        TestHttpResponse(200)
                    }
                    method == "GET" && "list-type=2" in query -> {
                        val prefixParam = query.split('&')
                            .firstOrNull { it.startsWith("prefix=") }
                            ?.removePrefix("prefix=")
                            ?.let { URLDecoder.decode(it, "UTF-8") }
                            .orEmpty()
                        val keys = storedObjects.keys
                            .filter { it.startsWith("s3/") }
                            .map { it.removePrefix("s3/") }
                            .filter { it.startsWith(prefixParam) }
                            .sorted()
                        val contentsXml = keys.joinToString("") { k -> "<Contents><Key>$k</Key></Contents>" }
                        val xml = """<?xml version="1.0" encoding="UTF-8"?><ListBucketResult>$contentsXml</ListBucketResult>"""
                        TestHttpResponse(200, xml.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/xml"))
                    }
                    method == "GET" -> {
                        val data = storedObjects["s3/$key"]
                        if (data == null) TestHttpResponse(404) else TestHttpResponse(200, data)
                    }
                    else -> TestHttpResponse(404)
                }
            }

            TestHttpResponse(404)
        }
        baseUrl = server.baseUrl
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `WebDavShardAdapter executes MKCOL, PUT, PROPFIND, GET, auth failure, 404, and 429`() = runTest {
        val vault = InMemorySecretVaultAdapter()
        vault.putSecret("dav_secret", "pass123")
        val dav = WebDavShardAdapter(
            config = WebDavConfig(baseUrl = "$baseUrl/dav", username = "user", useBearerAuth = false),
            secretVault = vault,
            secretAlias = "dav_secret",
        )

        assertTrue(dav.listShards("progress/B0001/").isEmpty())
        assertNull(dav.getShard("progress/B0001/pixel.json"))

        val payload = """{"uid":"B0001","ordinal":4}""".toByteArray()
        dav.putShard("progress/B0001/pixel.json", payload)
        assertTrue(recordedRequests.any { it.method == "MKCOL" && it.path == "/dav/progress/" })
        assertTrue(recordedRequests.any { it.method == "MKCOL" && it.path == "/dav/progress/B0001/" })

        assertEquals(listOf("progress/B0001/pixel.json"), dav.listShards("progress/B0001/"))
        assertArrayEquals(payload, dav.getShard("progress/B0001/pixel.json"))

        vault.putSecret("dav_secret", "wrong-pass")
        val authErr = runCatching { dav.getShard("progress/B0001/pixel.json") }
        assertTrue(authErr.exceptionOrNull()!!.message!!.contains("401"))

        vault.putSecret("dav_secret", "pass123")
        simulateRateLimit429 = true
        val rateErr = runCatching { dav.putShard("progress/B0001/pixel.json", payload) }
        assertTrue(rateErr.exceptionOrNull()!!.message!!.contains("429"))
    }

    @Test
    fun `S3R2ShardAdapter computes valid AWS SigV4 signatures and round-trips PUT, ListObjectsV2, and GET`() = runTest {
        val vault = InMemorySecretVaultAdapter()
        vault.putSecret("s3_secret", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        val fixedClock = Clock.fixed(Instant.parse("2026-11-28T09:00:00Z"), ZoneOffset.UTC)
        val s3 = S3R2ShardAdapter(
            config = S3R2Config(
                endpointUrl = baseUrl,
                bucket = "banread-bucket",
                region = "auto",
                accessKeyId = "AKIA123456",
                keyPrefix = "banread",
            ),
            secretVault = vault,
            secretAlias = "s3_secret",
            clock = fixedClock,
        )

        val signed1 = s3.buildSignedRequest(
            method = "PUT",
            objectKey = "banread/progress/B0001/pixel.json",
            queryParams = emptyList(),
            bodyBytes = """{"ok":true}""".toByteArray(),
            contentType = "application/json; charset=utf-8",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )
        assertTrue(signed1.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIA123456/20261128/auto/s3/aws4_request"))
        assertTrue(signed1.headers["Authorization"]!!.contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"))
        assertEquals(64, signed1.signature.length)
        assertTrue(signed1.canonicalRequest.contains("/banread-bucket/banread/progress/B0001/pixel.json"))

        val noteLine = """{"note_id":"n1","comment":"test"}""" + "\n"
        s3.putShard("notes/B0001/pixel.jsonl", noteLine.toByteArray())
        assertEquals(listOf("notes/B0001/pixel.jsonl"), s3.listShards("notes/B0001/"))
        assertArrayEquals(noteLine.toByteArray(), s3.getShard("notes/B0001/pixel.jsonl"))
        assertNull(s3.getShard("notes/B0001/missing.jsonl"))
    }

    @Test
    fun `red-line guard rejects bcbook, pdf, path traversal, non-shard prefixes, and oversized payloads across all StateSyncPort adapters`() = runTest {
        val vault = InMemorySecretVaultAdapter().apply {
            putSecret("dav_secret", "pass123")
            putSecret("s3_secret", "secret123")
        }
        val adapters: List<StateSyncPort> = listOf(
            InMemoryStateSyncAdapter(),
            WebDavShardAdapter(WebDavConfig("$baseUrl/dav", "user"), vault, "dav_secret"),
            S3R2ShardAdapter(S3R2Config(baseUrl, "banread-bucket", "auto", "AKIA123456"), vault, "s3_secret"),
        )

        val forbiddenPaths = listOf(
            "books/B0001.bcbook",
            "progress/B0001/book.pdf",
            "companion_cache/B0001/ch_01/chapter_map.json",
            "progress/../secrets/token.json",
            "notes/B0001/pixel.json",
            "progress/B0001/pixel.jsonl",
            "images/cover.png",
        )

        val oversized = ByteArray(2 * 1024 * 1024 + 1) { 0x41 }

        for (adapter in adapters) {
            for (badPath in forbiddenPaths) {
                val res = runCatching { adapter.putShard(badPath, "{}".toByteArray()) }
                assertTrue("Expected rejection for $badPath on ${adapter::class.simpleName}", res.isFailure)
                assertTrue((res.exceptionOrNull() as AppFailureException).failure is AppFailure.NetworkOrSyncConflict)
            }
            val sizeRes = runCatching { adapter.putShard("progress/B0001/pixel.json", oversized) }
            assertTrue("Expected >2MiB rejection on ${adapter::class.simpleName}", sizeRes.isFailure)
            val listRes = runCatching { adapter.listShards("companion_cache/") }
            assertTrue("Expected prefix rejection on ${adapter::class.simpleName}", listRes.isFailure)
        }
    }

    private data class RecordedHttpCall(
        val method: String,
        val path: String,
        val query: String,
        val authHeader: String,
        val amzDate: String,
        val amzSha256: String,
    )
}
