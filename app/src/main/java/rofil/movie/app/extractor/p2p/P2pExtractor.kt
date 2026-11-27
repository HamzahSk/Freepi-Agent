package rofil.movie.app.extractor.p2p

import rofil.movie.app.data.remote.PlayerMetadata
import rofil.movie.app.data.remote.StreamResolutionException

/** P2P retains LK21's existing player/API traversal and generic media metadata parsing. */
object P2pExtractor {
    fun parseApiResponse(json: String, apiUrl: String): String = PlayerMetadata.embed(json, apiUrl)

    fun parsePlayerPage(html: String, pageUrl: String) = PlayerMetadata.parse(html, pageUrl)

    fun requireNode(url: String) {
        if (!Regex("/iframe3/p2p/[^/]+/?$", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            throw StreamResolutionException("INITIAL_REQUEST: unsupported P2P video-node URL")
        }
    }
}
