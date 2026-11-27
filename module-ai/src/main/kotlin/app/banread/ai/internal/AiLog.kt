package app.banread.ai.internal

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Single log seam for provider traffic. Fixed [TAG] so `adb logcat -s BanreadAiProvider`
 * filters every LLM request outcome. Never log API keys, Authorization headers or raw
 * request bodies here.
 */
internal object AiLog {
    const val TAG = "BanreadAiProvider"

    fun request(providerId: String, model: String, url: String, hasOpenCodeSession: Boolean) {
        val parsed = url.toHttpUrlOrNull()
        Log.d(
            TAG,
            "request provider=$providerId model=$model host=${parsed?.host ?: "-"} " +
                "path=${parsed?.encodedPath ?: "-"} opencodeSession=$hasOpenCodeSession",
        )
    }

    fun failure(providerId: String, httpCode: Int?, url: String, type: String?, redactedBody: String) {
        val parsed: HttpUrl? = url.toHttpUrlOrNull()
        Log.w(
            TAG,
            "failure provider=$providerId http=${httpCode ?: "-"} host=${parsed?.host ?: "-"} " +
                "path=${parsed?.encodedPath ?: "-"} type=${type ?: "-"} body=${redactedBody.take(300)}",
        )
    }
}
