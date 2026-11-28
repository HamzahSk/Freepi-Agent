package rofil.movie.app.data.scrape

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Catalog navigation tokens are distinct from network/media URLs. */
object ExtensionRoutes {
    fun isRoute(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.matches(Regex("[a-z][a-z0-9-]*")) == true &&
            uri.scheme !in setOf("http", "https", "javascript", "data", "file", "content", "intent") &&
            uri.host in setOf("filter", "search", "list") && uri.rawUserInfo == null && uri.fragment == null
    }.getOrDefault(false)

    fun arguments(sourceId: String, value: String): JSONObject {
        val args = JSONObject().put("url", value)
        if (!isRoute(value)) return args
        val uri = URI(value)
        require(uri.scheme == sourceId) { "Catalog route belongs to another source" }
        uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).forEach { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val item = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            args.put(if (key == "q") "query" else key, item)
        }
        return args
    }
}
