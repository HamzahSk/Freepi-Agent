package com.freepi.lk21.data.scrape

import org.json.JSONObject

/**
 * The single place where the WebView <-> Kotlin data contract lives.
 *
 * Every JavaScript extraction produces one of these schemas. The Kotlin side
 * validates the envelope before it is allowed to reach [ScrapeMapper], so the
 * repositories never see half-built JSON or a "fake empty" page.
 */
enum class ScrapeKind(val schema: String, val assetName: String) {
    HOME(ScrapeSchemas.HOME, "home.js"),
    LIST(ScrapeSchemas.LIST, "list.js"),
    DETAIL(ScrapeSchemas.DETAIL, "detail.js"),
    STREAM(ScrapeSchemas.STREAM, "stream.js"),
}

object ScrapeSchemas {
    const val HOME = "freepi.home.v1"
    const val LIST = "freepi.list.v1"
    const val DETAIL = "freepi.detail.v1"
    const val STREAM = "freepi.stream.v1"
    const val ERROR = "freepi.error.v1"

    /** Name of the JavascriptInterface exposed to the hidden WebView. */
    const val BRIDGE_NAME = "FreepiScrapeBridge"
}

data class ScrapeRequest(
    val kind: ScrapeKind,
    val url: String,
    val referer: String? = null,
    val timeoutMs: Long = 35_000L,
    /** Number of *additional* attempts for transient failures. */
    val retries: Int = 1,
    val forceRefresh: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** STREAM only: load the real parent document, preserving iframe/CSP restrictions. */
    val embeddingPageUrl: String? = null,
    val streamMaxDepth: Int = 6,
    val streamProviderUrl: String? = null,
)

data class ScrapeSourceInfo(
    val requestedUrl: String,
    val finalUrl: String,
    val pageTitle: String?,
    val fetchedAt: Long,
)

data class ScrapeError(val code: String, val message: String)

data class ScrapeEnvelope(
    val schema: String,
    val source: ScrapeSourceInfo,
    val data: JSONObject,
    val raw: JSONObject,
)

object ScrapeErrorCodes {
    const val ACCESS_BLOCKED = "ACCESS_BLOCKED"
    const val NAVIGATION_FAILED = "NAVIGATION_FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val EMPTY_PAGE = "EMPTY_PAGE"
    const val MALFORMED = "MALFORMED"
    const val JS_ERROR = "JS_ERROR"
    const val WEBVIEW_ERROR = "WEBVIEW_ERROR"
}
