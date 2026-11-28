package app.banread.sync.internal

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side AES-256-GCM encryption for Channel 1 (R2 / Worker 24h E2EE Relay).
 * - 256-bit random key; key lives ONLY in URL `#k=<base64url>` fragment.
 * - Fresh 96-bit random IV per encrypted object prepended to ciphertext (`iv || ciphertext_with_128bit_tag`).
 * - AAD binds ciphertext to `(shareId, partName)` to prevent object substitution or tampering.
 */
internal object ShareAesGcmCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val secureRandom = SecureRandom()

    fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also { secureRandom.nextBytes(it) }

    fun encodeKeyBase64Url(keyBytes: ByteArray): String {
        require(keyBytes.size == KEY_BYTES) { "Expected 256-bit AES key" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)
    }

    fun decodeKeyBase64Url(encoded: String): ByteArray {
        val raw = encoded.trim()
        val decoded = runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull()
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("无效的端到端解密密钥 (#k)"))
        if (decoded.size != KEY_BYTES) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("端到端解密密钥长度异常"))
        }
        return decoded
    }

    fun encrypt(plaintext: ByteArray, keyBytes: ByteArray, shareId: String, partName: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val key: SecretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD("banread-share-v1:$shareId:$partName".toByteArray(Charsets.UTF_8))
        val cipherBytes = cipher.doFinal(plaintext)
        return iv + cipherBytes
    }

    fun decrypt(blob: ByteArray, keyBytes: ByteArray, shareId: String, partName: String): ByteArray {
        if (blob.size <= IV_BYTES + 16) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("密文数据损坏或被截断 ($partName)"))
        }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val cipherBytes = blob.copyOfRange(IV_BYTES, blob.size)
        val key: SecretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD("banread-share-v1:$shareId:$partName".toByteArray(Charsets.UTF_8))
            cipher.doFinal(cipherBytes)
        } catch (ex: Exception) {
            throw AppFailureException(
                AppFailure.NetworkOrSyncConflict("AES-GCM 解密校验失败：密钥不匹配或密文被篡改 ($partName)"),
            )
        }
    }

    data class ParsedR2ShareLink(
        val fetchBaseUrl: String,
        val shareId: String,
        val keyBytes: ByteArray,
    )

    fun parseShareLink(rawLink: String): ParsedR2ShareLink {
        val clean = rawLink.trim()
        val hashIdx = clean.indexOf('#')
        if (hashIdx <= 0) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("分享链接缺少 #k= 端到端解密密钥片段"))
        }
        val urlWithoutFragment = clean.substring(0, hashIdx).trimEnd('/')
        val fragment = clean.substring(hashIdx + 1)
        val keyParam = fragment.split('&')
            .firstOrNull { it.startsWith("k=") }
            ?.removePrefix("k=")
            ?.trim()
            .orEmpty()
        if (keyParam.isEmpty()) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("分享链接缺少 #k=<key> 参数"))
        }
        val keyBytes = decodeKeyBase64Url(keyParam)
        val normalizedBase = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(urlWithoutFragment)
        val shareId = normalizedBase.substringAfterLast('/').trim()
        if (shareId.isEmpty() || !shareId.matches(Regex("^[A-Za-z0-9_-]{4,64}$"))) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("分享链接中的 shareId 无效"))
        }
        return ParsedR2ShareLink(
            fetchBaseUrl = normalizedBase,
            shareId = shareId,
            keyBytes = keyBytes,
        )
    }
}
