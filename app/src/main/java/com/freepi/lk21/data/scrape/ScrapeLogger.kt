package com.freepi.lk21.data.scrape

import android.util.Log

/**
 * Structured, greppable logging for every scraping stage.
 *
 * `adb logcat -s FreepiScrape` shows one line per stage, together with the
 * request id, page kind, elapsed time and failure reason. No page content or
 * cookies are logged.
 */
object ScrapeLogger {

    private const val TAG = "FreepiScrape"

    fun stage(
        requestId: String,
        kind: ScrapeKind,
        stage: String,
        url: String? = null,
        elapsedMs: Long? = null,
        detail: String? = null,
    ) {
        runCatching { Log.d(TAG, build("stage", requestId, kind, stage, url, elapsedMs, detail)) }
    }

    fun failure(
        requestId: String,
        kind: ScrapeKind,
        stage: String,
        error: Throwable,
        url: String? = null,
        elapsedMs: Long? = null,
    ) {
        runCatching { Log.e(TAG, build("failure", requestId, kind, stage, url, elapsedMs,
            error.javaClass.simpleName)) }
    }

    fun warn(requestId: String, kind: ScrapeKind, stage: String, detail: String, url: String? = null) {
        runCatching { Log.w(TAG, build("warning", requestId, kind, stage, url, null, detail)) }
    }

    internal fun safeUrl(raw: String): String = runCatching {
        val uri = java.net.URI(raw)
        // Player slugs and path components can carry signed credentials too.
        "${uri.scheme}://${uri.host}/[redacted]"
    }.getOrDefault("[redacted]")

    private fun sanitize(value: String): String =
        value.replace(Regex("https?://[^\\s]+")) { safeUrl(it.value) }

    private fun build(
        level: String,
        requestId: String,
        kind: ScrapeKind,
        stage: String,
        url: String?,
        elapsedMs: Long?,
        detail: String?,
    ): String = buildString {
        append("level=").append(level)
        append(" id=").append(requestId.take(8))
        append(" kind=").append(kind.name.lowercase())
        append(" stage=").append(stage)
        if (url != null) {
            append(" url=").append(safeUrl(url))
            // Correlate resources at one origin without exposing signed path/query credentials.
            append(" urlId=").append(java.security.MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray()).take(6).joinToString("") { "%02x".format(it) })
        }
        if (elapsedMs != null) append(" elapsedMs=").append(elapsedMs)
        if (!detail.isNullOrBlank()) append(" detail=").append(sanitize(detail).take(300).replace('\n', ' '))
    }
}
