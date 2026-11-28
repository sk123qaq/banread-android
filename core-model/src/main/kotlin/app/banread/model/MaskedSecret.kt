package app.banread.model

/** Display-safe API key; private construction plus masking factories keep plaintext out of UI state, previews, and logs. */
@JvmInline
value class MaskedSecret private constructor(val display: String) {
    init {
        require(isMaskedDisplay(display)) { "MaskedSecret requires an already masked value" }
    }

    val isConfigured: Boolean
        get() = display != NOT_CONFIGURED && display != LOCAL_NOT_REQUIRED

    override fun toString(): String = display

    companion object {
        const val LOCAL_NOT_REQUIRED = "本地免 Key"
        const val NOT_CONFIGURED = "未配置 Key"
        private const val MASK = "••••"
        private const val PREVIEW_PREFIX = 4
        private val MASKED_PREVIEW_REGEX = Regex("""^.{0,$PREVIEW_PREFIX}$MASK.{0,$PREVIEW_PREFIX}$""")

        fun localNotRequired(): MaskedSecret = MaskedSecret(LOCAL_NOT_REQUIRED)

        fun notConfigured(): MaskedSecret = MaskedSecret(NOT_CONFIGURED)

        fun fromRaw(rawApiKey: String?, requiresApiKey: Boolean = true): MaskedSecret {
            if (!requiresApiKey) return localNotRequired()
            val trimmed = rawApiKey?.trim().orEmpty()
            if (trimmed.isEmpty()) return notConfigured()
            if (trimmed.length < 8) return MaskedSecret(MASK)
            return MaskedSecret("${trimmed.take(PREVIEW_PREFIX)}$MASK${trimmed.takeLast(PREVIEW_PREFIX)}")
        }

        fun forSummary(hasApiKey: Boolean, requiresApiKey: Boolean): MaskedSecret = when {
            !requiresApiKey -> localNotRequired()
            hasApiKey -> MaskedSecret(MASK)
            else -> notConfigured()
        }

        private fun isMaskedDisplay(value: String): Boolean =
            value == LOCAL_NOT_REQUIRED ||
                value == NOT_CONFIGURED ||
                value == MASK ||
                MASKED_PREVIEW_REGEX.matches(value)
    }
}

/** Plaintext key while editing; [toString] is redacted so state dumps and crash reports cannot leak it. */
@JvmInline
value class ApiKeyDraft private constructor(val raw: String) {
    val isBlank: Boolean get() = raw.isBlank()

    fun trimmedOrNull(): String? = raw.trim().takeIf { it.isNotEmpty() }

    override fun toString(): String = "ApiKeyDraft(***)"

    companion object {
        val Empty = ApiKeyDraft("")

        fun of(raw: String): ApiKeyDraft = ApiKeyDraft(raw)
    }
}
