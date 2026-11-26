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
        Log.d(TAG, build("stage", requestId, kind, stage, url, elapsedMs, detail))
    }

    fun failure(
        requestId: String,
        kind: ScrapeKind,
        stage: String,
        error: Throwable,
        url: String? = null,
        elapsedMs: Long? = null,
    ) {
        Log.e(
            TAG,
            build(
                "failure",
                requestId,
                kind,
                stage,
                url,
                elapsedMs,
                "${error.javaClass.simpleName}: ${error.message}",
            ),
            error,
        )
    }

    fun warn(requestId: String, kind: ScrapeKind, stage: String, detail: String, url: String? = null) {
        Log.w(TAG, build("warning", requestId, kind, stage, url, null, detail))
    }

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
        if (url != null) append(" url=").append(url.take(180))
        if (elapsedMs != null) append(" elapsedMs=").append(elapsedMs)
        if (!detail.isNullOrBlank()) append(" detail=").append(detail.take(300).replace('\n', ' '))
    }
}
