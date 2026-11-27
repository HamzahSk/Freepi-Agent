package rofil.movie.app.extractor.hydrax

import org.json.JSONObject
import rofil.movie.app.data.remote.PlayerMetadata
import rofil.movie.app.data.remote.StreamResolutionException

/** Hydrax's captured /iframe3/hydrax/{id} -> POST /api.php -> embedUrl handoff. */
object HydraxExtractor {
    data class ApiRequest(val url: String, val host: String, val id: String)

    fun apiRequest(html: String, videoNodeUrl: String): ApiRequest? {
        val popup = PlayerMetadata.popup(html, videoNodeUrl) ?: return null
        if (!popup.host.equals("hydrax", ignoreCase = true)) return null
        val pathId = Regex("^/iframe3/hydrax/([^/]+)/?$", RegexOption.IGNORE_CASE)
            .find(runCatching { java.net.URI(videoNodeUrl).path }.getOrDefault(""))?.groupValues?.getOrNull(1)
            ?: return null
        if (popup.id != pathId) return null
        return ApiRequest(popup.url, popup.host, popup.id)
    }

    fun parseApiResponse(json: String, apiUrl: String): String {
        val payload = runCatching { JSONObject(json) }.getOrElse {
            throw StreamResolutionException("API_RESPONSE: Hydrax returned invalid JSON", it)
        }
        val embed = payload.opt("embedUrl") as? String
            ?: throw StreamResolutionException("API_RESPONSE: Hydrax response is missing embedUrl")
        return PlayerMetadata.absolute(embed, apiUrl)
            ?: throw StreamResolutionException("EMBED_URL: Hydrax returned an invalid embedUrl")
    }

    fun parsePlayerPage(html: String, pageUrl: String) = PlayerMetadata.parse(html, pageUrl)
}
