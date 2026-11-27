package com.freepi.lk21.data.scrape

import java.net.URI

/** Conservative filter: keep CSS, scripts, verification, API, config and media requests. */
object ScrapeResourcePolicy {
    private val telemetry = setOf("static.cloudflareinsights.com", "www.google-analytics.com",
        "www.googletagmanager.com", "google-analytics.com")
    fun skip(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host in telemetry || Regex("""\.(?:png|jpe?g|gif|webp|ico|avif|woff2?|ttf|otf)$""",
            RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty())
    }.getOrDefault(false)
}
