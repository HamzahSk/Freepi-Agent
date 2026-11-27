package com.freepi.lk21.data.scrape

import android.content.Context
import com.freepi.lk21.data.remote.ParseException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the DOM-extraction JavaScript from `assets/scrape/`. Keeping the
 * selectors in plain `.js` files makes them inspectable and lets the Playwright
 * verification script run exactly the same code that the WebView runs.
 */
class ScrapeScriptLoader(private val context: Context) {

    private val common: String by lazy { readAsset("scrape/common.js") }
    private val pageScripts = ConcurrentHashMap<ScrapeKind, String>()

    fun script(request: ScrapeRequest, requestId: String): String {
        val page = pageScripts.getOrPut(request.kind) {
            readAsset("scrape/${request.kind.assetName}")
        }
        return buildString {
            append("window.__freepiRequestId=").append(JSONObject.quote(requestId)).append(";\n")
            append("window.__freepiRequestUrl=").append(JSONObject.quote(request.url)).append(";\n")
            append("window.__freepiProviderUrl=").append(JSONObject.quote(request.streamProviderUrl ?: request.url)).append(";\n")
            append(common).append('\n')
            append(page)
        }
    }

    private fun readAsset(path: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        throw ParseException("Could not load the scraping script '$path'.", t)
    }
}
