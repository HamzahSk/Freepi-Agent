package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.remote.AccessBlockedException
import com.freepi.lk21.data.remote.NetworkUnavailableException
import com.freepi.lk21.data.remote.ParseException
import com.freepi.lk21.data.remote.ScrapeTimeoutException
import com.freepi.lk21.data.remote.StreamResolutionException
import com.freepi.lk21.data.remote.WebViewUnavailableException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Validation gate between the WebView JavaScript payload and the Kotlin data
 * models. Validation is deliberately UI- and Android-independent so it can be
 * exercised by plain JVM unit tests.
 *
 * Rules:
 *  - A payload must declare the expected `schema` and `ok == true`.
 *  - A page that contains no recognizable cards when cards were expected is an
 *    error, never a silent empty list (except a genuinely empty search result,
 *    which the JavaScript marks with `emptyHint`).
 *  - URLs that end up in models must be http(s) or root-relative.
 */
object ScrapeValidator {

    fun parseEnvelope(json: JSONObject, expected: ScrapeKind): ScrapeEnvelope {
        val schema = json.optString("schema").trim()
        val source = sourceInfo(json.optJSONObject("source"))

        if (!json.optBoolean("ok", false)) {
            throw errorFor(json.optJSONObject("error"))
        }
        if (schema == ScrapeSchemas.ERROR) {
            throw ParseException("The scraping engine returned an error payload.")
        }
        if (schema != expected.schema) {
            throw ParseException(
                "Unexpected scraping schema '$schema' (expected '${expected.schema}')."
            )
        }
        val data = json.optJSONObject("data")
            ?: throw ParseException("The scraping payload does not contain a data object.")
        return ScrapeEnvelope(schema = schema, source = source, data = data, raw = json)
    }

    // ------------------------------------------------------------------ home

    fun requireHome(envelope: ScrapeEnvelope): JSONObject {
        val data = envelope.data
        val sections = data.optJSONObject("sections")
            ?: throw ParseException("Home payload is missing the sections object.")

        var valid = 0
        for (key in HOME_SECTIONS) {
            valid += countValidCards(sections.optJSONArray(key))
        }
        if (valid == 0) {
            throw ParseException(
                "The source returned a page without recognizable movie cards."
            )
        }
        validateFilters(data.optJSONObject("filters"))
        return data
    }

    // ------------------------------------------------------------------ list

    fun requireList(envelope: ScrapeEnvelope, isSearch: Boolean): JSONObject {
        val data = envelope.data
        val items = data.optJSONArray("items")
            ?: throw ParseException("List payload is missing the items array.")

        val page = data.optInt("page", 1).coerceAtLeast(1)
        val totalPages = data.optInt("totalPages", page).coerceAtLeast(page)
        val emptyHint = data.optBoolean("emptyHint", false)
        val valid = countValidCards(items)

        if (valid == 0 && !emptyHint && !isSearch && page <= 1) {
            throw ParseException(
                "The source returned a catalog page without recognizable movie cards."
            )
        }
        if (valid == 0 && !emptyHint && page > 1) {
            throw ParseException(
                "Page $page of the catalog could not be read (no cards found)."
            )
        }

        data.optString("nextPageUrl").takeIf { it.isNotBlank() }?.let { next ->
            if (!isAcceptableUrl(next)) {
                throw ParseException("Invalid next-page URL in list payload: $next")
            }
        }
        if (totalPages < page) {
            throw ParseException("Pagination data is inconsistent (page $page of $totalPages).")
        }
        return data
    }

    // ---------------------------------------------------------------- detail

    fun requireDetail(envelope: ScrapeEnvelope): JSONObject {
        val data = envelope.data
        val ldTitle = data.optJSONObject("jsonLd")?.optString("name").orEmpty()
        val title = data.optString("title").ifBlank { ldTitle }.trim()
        if (title.isBlank()) {
            throw ParseException("Could not read the movie details (no title in the page).")
        }

        validateSources(data.optJSONArray("sources"))
        validateSubtitles(data.optJSONArray("subtitles"))
        validateCards(data.optJSONArray("related"))

        data.optString("redirectUrl").takeIf { it.isNotBlank() }?.let {
            if (!isAcceptableUrl(it)) throw ParseException("Invalid redirect URL in detail payload.")
        }
        return data
    }

    // ---------------------------------------------------------------- stream

    fun requireStream(envelope: ScrapeEnvelope): JSONObject {
        val data = envelope.data
        val media = data.optJSONArray("media")
        val iframes = data.optJSONArray("iframes")
        val scripts = data.optJSONArray("scripts")
        validateSubtitles(data.optJSONArray("subtitles"))

        val mediaCount = media?.let(::countValidUrls) ?: 0
        val frameCount = iframes?.let(::countAcceptableUrls) ?: 0
        val scriptCount = scripts?.length() ?: 0
        if (mediaCount == 0 && frameCount == 0 && scriptCount == 0) {
            throw StreamResolutionException(
                "MEDIA_METADATA: the provider page did not expose accessible media metadata."
            )
        }
        return data
    }

    // --------------------------------------------------------------- helpers

    private val HOME_SECTIONS = listOf("featured", "popular", "latest", "recommended", "series")

    private fun sourceInfo(json: JSONObject?): ScrapeSourceInfo {
        val now = System.currentTimeMillis()
        return ScrapeSourceInfo(
            requestedUrl = json?.optString("requestedUrl").orEmpty(),
            finalUrl = json?.optString("finalUrl").orEmpty(),
            pageTitle = json?.optString("title")?.takeIf { it.isNotBlank() },
            fetchedAt = runCatching { json?.optLong("fetchedAt", now) ?: now }.getOrDefault(now),
        )
    }

    private fun errorFor(json: JSONObject?): Exception {
        val code = json?.optString("code").orEmpty()
        val message = json?.optString("message").orEmpty().ifBlank {
            "The scraping engine could not read the page."
        }
        return when (code) {
            ScrapeErrorCodes.ACCESS_BLOCKED -> AccessBlockedException(message)
            ScrapeErrorCodes.TIMEOUT -> ScrapeTimeoutException(message)
            ScrapeErrorCodes.NAVIGATION_FAILED -> NetworkUnavailableException(message)
            ScrapeErrorCodes.WEBVIEW_ERROR -> WebViewUnavailableException(message)
            else -> ParseException(message)
        }
    }

    private fun validateCards(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val slug = item.optString("slug")
            val title = item.optString("title")
            val url = item.optString("url")
            if (slug.isBlank() || title.isBlank()) continue
            if (url.isNotBlank() && !isAcceptableUrl(url)) {
                throw ParseException("Invalid card URL in payload: $url")
            }
        }
    }

    private fun countValidCards(array: JSONArray?): Int {
        if (array == null) return 0
        var count = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("slug").isNotBlank() && item.optString("title").isNotBlank()) {
                count++
            }
        }
        return count
    }

    private fun countValidUrls(array: JSONArray): Int {
        var count = 0
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            val url = when (value) {
                is String -> value
                is JSONObject -> value.optString("url")
                else -> ""
            }
            if (isAcceptableUrl(url)) count++
        }
        return count
    }

    private fun countAcceptableUrls(array: JSONArray): Int {
        var count = 0
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (isAcceptableUrl(value)) count++
        }
        return count
    }

    private fun validateSources(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val embed = item.optString("embedUrl")
            if (embed.isBlank()) continue
            if (!isAcceptableUrl(embed)) {
                throw ParseException("Invalid provider embed URL: $embed")
            }
        }
    }

    private fun validateSubtitles(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isNotBlank() && !isAcceptableUrl(url)) {
                throw ParseException("Invalid subtitle URL: $url")
            }
        }
    }

    private fun validateFilters(filters: JSONObject?) {
        if (filters == null) return
        for (key in listOf("genres", "countries", "years", "qualities")) {
            val array = filters.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path")
                if (path.isBlank()) continue
                if (!isAcceptableUrl(path) && !path.startsWith("/")) {
                    throw ParseException("Invalid filter path: $path")
                }
            }
        }
    }

    fun isAcceptableUrl(url: String): Boolean {
        val value = url.trim()
        return value.startsWith("http://") ||
            value.startsWith("https://") ||
            (value.startsWith("/") && !value.startsWith("//"))
    }
}
