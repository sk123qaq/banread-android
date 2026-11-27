package app.banread.ai.internal

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * OpenCode Go routes each inference request by conversation. Every request must carry a
 * stable `x-opencode-session` value; without it the gateway answers HTTP 400
 * `MissingSessionID` instead of generating a completion.
 *
 * The value is derived (hashed) from our own conversation id so the header stays stable
 * for the whole thread without exposing the raw thread UUID.
 */
internal object OpenCodeSession {
    const val HEADER = "x-opencode-session"
    const val USER_AGENT = "banread-android"
    const val SESSION_PREFIX = "ses_"

    fun isOpenCodeHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "opencode.ai" || host.endsWith(".opencode.ai")
    }

    fun headerValue(conversationId: String?): String {
        val source = conversationId?.trim()?.takeIf { it.isNotEmpty() } ?: "banread-provider"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val out = StringBuilder(SESSION_PREFIX.length + 32)
        out.append(SESSION_PREFIX)
        for (index in 0 until 16) {
            val value = digest[index].toInt() and 0xFF
            out.append(hex[value ushr 4]).append(hex[value and 0x0F])
        }
        return out.toString()
    }
}
