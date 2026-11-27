package app.banread.ai.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns provider HTTP error bodies into short, human-readable messages.
 *
 * Error bodies differ per gateway: OpenAI uses `{"error":{"message":…}}`, Anthropic and
 * OpenCode use `{"type":"error","error":{"type":…,"message":…}}`. The UI must never show
 * the raw JSON body, so this object extracts the structured fields and maps the common
 * auth/entitlement/session cases to actionable text.
 */
internal object ProviderErrorText {

    data class Parsed(
        val type: String?,
        val message: String?,
        val code: String?,
    )

    fun parse(rawBody: String?): Parsed {
        val body = rawBody?.trim().orEmpty()
        if (body.isEmpty()) return Parsed(null, null, null)
        val root = runCatching { HttpStreamingSupport.jsonParser.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return Parsed(null, null, null)
        val nested = root["error"] as? JsonObject
        val nestedErrorMessage = (root["error"] as? JsonPrimitive)?.contentOrNull
        val type = nested.stringOrNull("type")
            ?: root.stringOrNull("type")?.takeIf { !it.equals("error", ignoreCase = true) }
        val message = nested.stringOrNull("message")
            ?: nested.stringOrNull("detail")
            ?: root.stringOrNull("message")
            ?: root.stringOrNull("detail")
            ?: nestedErrorMessage
        val code = nested.stringOrNull("code") ?: root.stringOrNull("code")
        return Parsed(
            type = type?.trim()?.takeIf { it.isNotEmpty() },
            message = message?.trim()?.takeIf { it.isNotEmpty() },
            code = code?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * @param fallbackMessage exception message used only when the body has no readable text.
     *        Raw JSON/HTML bodies are deliberately ignored so they never reach the UI.
     */
    fun readable(httpCode: Int?, rawBody: String?, fallbackMessage: String? = null): String {
        val parsed = parse(rawBody)
        val type = parsed.type.orEmpty()
        val typeLower = type.lowercase()
        val detail = sanitize(parsed.message) ?: sanitize(fallbackMessage)?.takeIf { !looksStructured(it) }
        val detailLower = detail?.lowercase().orEmpty()

        return when {
            typeLower == "missingsessionid" || detailLower.contains("x-opencode-session") ->
                "OpenCode 要求本次会话携带 x-opencode-session 标识，当前请求缺少该标识；请将 App 更新到最新版本后重试。"

            typeLower == "entitlementerror" ||
                detailLower.contains("payment method") ||
                detailLower.contains("subscription ended") ||
                detailLower.contains("subscription required") ->
                "OpenCode 订阅不可用：订阅可能已到期，或付款方式需要重新授权。请到 OpenCode 控制台检查订阅状态。"

            httpCode == 401 ->
                "鉴权失败（HTTP 401）：请检查 API Key 是否正确，或该 Key 是否已失效。"

            httpCode == 403 ->
                "无权访问该模型（HTTP 403）：请检查 API Key 的权限或订阅状态。"

            httpCode == 429 ->
                "请求过于频繁或额度不足（HTTP 429），请稍后重试。"

            httpCode != null && httpCode in 500..599 ->
                "模型服务暂时不可用（HTTP $httpCode），请稍后重试。"

            !detail.isNullOrBlank() ->
                "模型请求被拒绝（HTTP ${httpCode ?: "-"}）：$detail"

            httpCode != null ->
                "模型请求失败（HTTP $httpCode），请稍后重试。"

            else ->
                "模型请求失败，请检查网络与模型配置后重试。"
        }
    }

    private fun sanitize(raw: String?): String? {
        val compact = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return compact.take(200).takeIf { it.isNotEmpty() }
    }

    private fun looksStructured(raw: String): Boolean {
        val first = raw.trimStart().firstOrNull() ?: return false
        return first == '{' || first == '[' || first == '<'
    }

    private fun JsonObject?.stringOrNull(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
